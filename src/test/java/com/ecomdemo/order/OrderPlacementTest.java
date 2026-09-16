package com.ecomdemo.order;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.ecomdemo.cart.Cart;
import com.ecomdemo.cart.CartService;
import com.ecomdemo.common.ConflictException;
import com.ecomdemo.order.dto.OrderItemResponse;
import com.ecomdemo.order.dto.OrderResponse;
import com.ecomdemo.product.Product;
import com.ecomdemo.support.TestFixtures;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link OrderPlacement} - the checkout rules, which are the most valuable thing in
 * the application to pin down. These moved here from {@code OrderServiceTest} in Phase 6, along with
 * the code they cover, when the retry was split out of the transaction.
 *
 * <p>The {@link Clock} is not mocked but substituted with {@code Clock.fixed}. A mock would need
 * stubbing and would still answer questions about interactions nobody cares about; a fixed clock is
 * a real implementation with known behaviour, which makes {@code placedAt} exactly assertable.
 *
 * <p>{@code OrderAuditService} <em>is</em> mocked: it is a collaborating service this feature owns,
 * and what matters here is that checkout asks it to record the right outcome, not how it stores it.
 * Its {@code REQUIRES_NEW} behaviour cannot be proven without a real transaction, which is why
 * {@code OrderAuditRollbackTest} exists as well.
 */
@ExtendWith(MockitoExtension.class)
class OrderPlacementTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:15:30Z");

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private CartService cartService;

    @Mock
    private OrderAuditService orderAuditService;

    private OrderPlacement orderPlacement;

    private Cart cart;
    private Product keyboard;
    private Product monitor;

    @BeforeEach
    void setUp() {
        orderPlacement = new OrderPlacement(
                orderRepository, cartService, orderAuditService, Clock.fixed(NOW, ZoneOffset.UTC));
        cart = new Cart(Cart.SHARED_CART_ID);
        keyboard = TestFixtures.product(1L, "Mechanical Keyboard", "129.99", 40);
        monitor = TestFixtures.product(3L, "27\" 4K Monitor", "399.00", 15);
    }

    private void cartIsReturned() {
        given(cartService.requireCart()).willReturn(cart);
    }

    private void saveEchoesBackWithId(long id) {
        given(orderRepository.save(any(Order.class)))
                .willAnswer(invocation -> TestFixtures.withId(invocation.getArgument(0), id));
    }

    @Nested
    class PlaceOnce {

        @Test
        void placeOnce_withItemsInTheCart_reducesStockSnapshotsPricesAndEmptiesTheCart() {
            // GIVEN - 2 keyboards and 1 monitor
            cartIsReturned();
            saveEchoesBackWithId(1L);
            cart.addOrIncrease(keyboard, 2);
            cart.addOrIncrease(monitor, 1);

            // WHEN
            OrderResponse order = orderPlacement.placeOnce();

            // THEN - the order reflects the cart
            assertThat(order.id()).isEqualTo(1L);
            assertThat(order.placedAt()).isEqualTo(NOW);
            assertThat(order.totalAmount()).isEqualByComparingTo("658.98");
            assertThat(order.items())
                    .extracting(OrderItemResponse::productName, OrderItemResponse::quantity)
                    .containsExactly(tuple("Mechanical Keyboard", 2), tuple("27\" 4K Monitor", 1));

            // AND stock fell by exactly the quantities ordered
            assertThat(keyboard.getStockQuantity()).isEqualTo(38);
            assertThat(monitor.getStockQuantity()).isEqualTo(14);

            // AND the cart became the order
            assertThat(cart.isEmpty()).isTrue();
        }

        @Test
        void placeOnce_afterThePriceChanges_keepsThePriceThatWasChargedAtCheckout() {
            // GIVEN
            cartIsReturned();
            saveEchoesBackWithId(1L);
            cart.addOrIncrease(keyboard, 1);

            // WHEN
            OrderResponse order = orderPlacement.placeOnce();
            // ...and the catalogue is repriced afterwards
            keyboard.setPrice(new BigDecimal("999.99"));
            keyboard.setName("Renamed Keyboard");

            // THEN - history is not rewritten, because OrderItem copied both at checkout
            assertThat(order.items().getFirst().unitPrice()).isEqualByComparingTo("129.99");
            assertThat(order.items().getFirst().productName()).isEqualTo("Mechanical Keyboard");
        }

        @Test
        void placeOnce_withAnEmptyCart_throwsConflictAndSavesNothing() {
            // GIVEN
            cartIsReturned();

            // WHEN / THEN
            assertThatThrownBy(() -> orderPlacement.placeOnce())
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Cannot place an order: the cart is empty");
            verify(orderRepository, never()).save(any());
        }

        @Test
        void placeOnce_whenOneLineExceedsStock_throwsConflictAndChangesNothingAtAll() {
            // GIVEN - line 1 is fine, line 2 is not. The monitor's stock dropped to 0 since the
            // customer filled their cart.
            cartIsReturned();
            cart.addOrIncrease(keyboard, 2);
            cart.addOrIncrease(monitor, 1);
            monitor.setStockQuantity(0);

            // WHEN / THEN
            assertThatThrownBy(() -> orderPlacement.placeOnce())
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("27\" 4K Monitor")
                    .hasMessageContaining("Only 0 unit(s)");

            // AND nothing was half-done: this is what validating the whole cart BEFORE mutating
            // anything buys. Without it, the keyboard's stock would already have been decremented.
            assertThat(keyboard.getStockQuantity()).isEqualTo(40);
            assertThat(cart.isEmpty()).isFalse();
            verify(orderRepository, never()).save(any());
        }

        @Test
        void placeOnce_whenTheCartHoldsExactlyTheRemainingStock_succeedsAndLeavesZero() {
            // GIVEN - the boundary: 40 of 40
            cartIsReturned();
            saveEchoesBackWithId(1L);
            cart.addOrIncrease(keyboard, 40);

            // WHEN
            orderPlacement.placeOnce();

            // THEN - "enough stock" is >=, not >
            assertThat(keyboard.getStockQuantity()).isZero();
        }
    }


    @Nested
    class Auditing {

        @Test
        void placeOnce_whenTheOrderSucceeds_recordsAPlacedAudit() {
            // GIVEN
            cartIsReturned();
            saveEchoesBackWithId(7L);
            cart.addOrIncrease(keyboard, 1);

            // WHEN
            orderPlacement.placeOnce();

            // THEN the audit names the order it describes
            verify(orderAuditService).record(eq(OrderAudit.Outcome.PLACED), any(), eq(7L));
        }

        @Test
        void placeOnce_withAnEmptyCart_recordsAnEmptyCartAuditBeforeThrowing() {
            // GIVEN
            cartIsReturned();

            // WHEN / THEN
            assertThatThrownBy(() -> orderPlacement.placeOnce()).isInstanceOf(ConflictException.class);

            // AND the attempt is recorded even though the transaction around it will roll back -
            // which is exactly what REQUIRES_NEW on the audit service is for.
            verify(orderAuditService).record(eq(OrderAudit.Outcome.EMPTY_CART), any(), eq(null));
        }

        @Test
        void placeOnce_whenStockIsShort_recordsAnInsufficientStockAudit() {
            // GIVEN
            cartIsReturned();
            cart.addOrIncrease(monitor, 1);
            monitor.setStockQuantity(0);

            // WHEN / THEN
            assertThatThrownBy(() -> orderPlacement.placeOnce()).isInstanceOf(ConflictException.class);
            verify(orderAuditService).record(eq(OrderAudit.Outcome.INSUFFICIENT_STOCK), any(), eq(null));
        }
    }
}
