package com.ecomdemo.order;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.ecomdemo.cart.Cart;
import com.ecomdemo.cart.CartService;
import com.ecomdemo.common.ConflictException;
import com.ecomdemo.common.NotFoundException;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link OrderService} - the checkout rules, which are the most valuable thing in the
 * application to pin down.
 *
 * <p>The {@link Clock} is not mocked but substituted with {@code Clock.fixed}. A mock would need
 * stubbing and would still answer questions about interactions nobody cares about; a fixed clock is
 * a real implementation with known behaviour, which makes {@code placedAt} exactly assertable. That
 * distinction - stub versus mock - is why {@code Clock} was injected in Phase 0 rather than calling
 * {@code Instant.now()} inline.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:15:30Z");

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private CartService cartService;

    private OrderService orderService;

    private Cart cart;
    private Product keyboard;
    private Product monitor;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, cartService, Clock.fixed(NOW, ZoneOffset.UTC));
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
    class PlaceOrder {

        @Test
        void placeOrder_withItemsInTheCart_reducesStockSnapshotsPricesAndEmptiesTheCart() {
            // GIVEN - 2 keyboards and 1 monitor
            cartIsReturned();
            saveEchoesBackWithId(1L);
            cart.addOrIncrease(keyboard, 2);
            cart.addOrIncrease(monitor, 1);

            // WHEN
            OrderResponse order = orderService.placeOrder();

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
        void placeOrder_afterThePriceChanges_keepsThePriceThatWasChargedAtCheckout() {
            // GIVEN
            cartIsReturned();
            saveEchoesBackWithId(1L);
            cart.addOrIncrease(keyboard, 1);

            // WHEN
            OrderResponse order = orderService.placeOrder();
            // ...and the catalogue is repriced afterwards
            keyboard.setPrice(new BigDecimal("999.99"));
            keyboard.setName("Renamed Keyboard");

            // THEN - history is not rewritten, because OrderItem copied both at checkout
            assertThat(order.items().getFirst().unitPrice()).isEqualByComparingTo("129.99");
            assertThat(order.items().getFirst().productName()).isEqualTo("Mechanical Keyboard");
        }

        @Test
        void placeOrder_withAnEmptyCart_throwsConflictAndSavesNothing() {
            // GIVEN
            cartIsReturned();

            // WHEN / THEN
            assertThatThrownBy(() -> orderService.placeOrder())
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Cannot place an order: the cart is empty");
            verify(orderRepository, never()).save(any());
        }

        @Test
        void placeOrder_whenOneLineExceedsStock_throwsConflictAndChangesNothingAtAll() {
            // GIVEN - line 1 is fine, line 2 is not. The monitor's stock dropped to 0 since the
            // customer filled their cart.
            cartIsReturned();
            cart.addOrIncrease(keyboard, 2);
            cart.addOrIncrease(monitor, 1);
            monitor.setStockQuantity(0);

            // WHEN / THEN
            assertThatThrownBy(() -> orderService.placeOrder())
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
        void placeOrder_whenTheCartHoldsExactlyTheRemainingStock_succeedsAndLeavesZero() {
            // GIVEN - the boundary: 40 of 40
            cartIsReturned();
            saveEchoesBackWithId(1L);
            cart.addOrIncrease(keyboard, 40);

            // WHEN
            orderService.placeOrder();

            // THEN - "enough stock" is >=, not >
            assertThat(keyboard.getStockQuantity()).isZero();
        }
    }

    @Nested
    class FindAll {

        @Test
        void findAll_whenOrdersExist_returnsThemAsResponses() {
            // GIVEN
            Order order = TestFixtures.withId(new Order(NOW), 1L);
            order.addItem(new OrderItem(order, keyboard, 2));
            given(orderRepository.findAllWithItems()).willReturn(List.of(order));

            // WHEN / THEN
            assertThat(orderService.findAll()).singleElement().satisfies(response -> {
                assertThat(response.id()).isEqualTo(1L);
                assertThat(response.totalAmount()).isEqualByComparingTo("259.98");
            });
        }

        @Test
        void findAll_whenThereAreNoOrders_returnsEmptyList() {
            // GIVEN
            given(orderRepository.findAllWithItems()).willReturn(List.of());

            // WHEN / THEN
            assertThat(orderService.findAll()).isEmpty();
        }
    }

    @Nested
    class FindById {

        @Test
        void findById_whenTheOrderExists_returnsIt() {
            // GIVEN
            Order order = TestFixtures.withId(new Order(NOW), 5L);
            order.addItem(new OrderItem(order, keyboard, 1));
            given(orderRepository.findByIdWithItems(5L)).willReturn(Optional.of(order));

            // WHEN / THEN
            OrderResponse found = orderService.findById(5L);
            assertThat(found.id()).isEqualTo(5L);
            assertThat(found.placedAt()).isEqualTo(NOW);
            assertThat(found.items()).hasSize(1);
        }

        @Test
        void findById_whenTheOrderIsMissing_throwsNotFound() {
            // GIVEN
            given(orderRepository.findByIdWithItems(42L)).willReturn(Optional.empty());

            // WHEN / THEN
            assertThatThrownBy(() -> orderService.findById(42L))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Order 42 not found");
        }
    }
}
