package com.ecomdemo.order;

import java.util.List;
import java.util.Map;

import com.ecomdemo.cart.Cart;
import com.ecomdemo.cart.CartService;
import com.ecomdemo.order.client.CatalogClient;
import com.ecomdemo.order.client.CatalogProduct;
import com.ecomdemo.order.client.InventoryClient;
import com.ecomdemo.order.client.ReservationRequest;
import com.ecomdemo.order.dto.OrderResponse;
import com.ecomdemo.shared.ConflictException;
import com.ecomdemo.support.TestFixtures;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The checkout rules, which are the most valuable thing in this system to pin down - and which are
 * now a distributed operation.
 *
 * <h2>What these tests are about after Phase 20</h2>
 *
 * Half of them are unchanged in spirit: an empty cart is refused, prices are snapshotted, the order
 * reflects the cart. The other half are new and are the reason this class matters, because they cover
 * the failure modes that only exist once two of the four steps are in other processes:
 *
 * <ul>
 *   <li>inventory-service says 409 - the shopper's cart no longer matches reality;
 *   <li>inventory-service cannot be reached at all - nobody's fault, and must not be reported as if
 *       the cart were at fault;
 *   <li>catalog-service cannot be reached - there is no cached price to fall back on and charging
 *       from a stale one would be worse than failing;
 *   <li><strong>the order fails to save after the stock was reserved</strong> - the one case no
 *       transaction can cover, and the one this phase leaves only partly solved.
 * </ul>
 *
 * <p>{@code OrderWriter} is mocked here. It owns the transaction and is unit-tested through the
 * integration suite where a real one exists; what this class is about is the orchestration around it,
 * including what happens when it throws.
 */
@ExtendWith(MockitoExtension.class)
class OrderPlacementTest {

    @Mock
    private CartService cartService;

    @Mock
    private CatalogClient catalogClient;

    @Mock
    private InventoryClient inventoryClient;

    @Mock
    private OrderWriter orderWriter;

    @Mock
    private OrderAuditService orderAuditService;

    private OrderPlacement orderPlacement;

    /** The signed-in shopper these tests act as. */
    private static final Long CUSTOMER_ID = 42L;

    private static final CatalogProduct KEYBOARD =
            TestFixtures.product(1L, "Mechanical Keyboard", "129.99");
    private static final CatalogProduct MONITOR =
            TestFixtures.product(3L, "27\" 4K Monitor", "399.00");

    private Cart cart;

    @BeforeEach
    void setUp() {
        orderPlacement = new OrderPlacement(
                cartService, catalogClient, inventoryClient, orderWriter, orderAuditService);
        cart = new Cart(CUSTOMER_ID);
    }

    private void cartIsReturned() {
        given(cartService.findCart(CUSTOMER_ID)).willReturn(java.util.Optional.of(cart));
    }

    private void catalogueIsUp() {
        given(catalogClient.findAll()).willReturn(List.of(KEYBOARD, MONITOR));
    }

    private void orderSavesSuccessfully() {
        given(orderWriter.saveOrder(eq(CUSTOMER_ID), anyList(), anyList(), anyMap()))
                .willReturn(new OrderResponse(1L, null, List.of(), TestFixtures.money("0.00")));
    }

    @Nested
    class TheHappyPath {

        @Test
        void placeOnce_withItemsInTheCart_pricesReservesAndSaves() {
            // GIVEN - 2 keyboards and 1 monitor
            cartIsReturned();
            catalogueIsUp();
            orderSavesSuccessfully();
            cart.addOrIncrease(KEYBOARD.id(), 2);
            cart.addOrIncrease(MONITOR.id(), 1);

            // WHEN
            orderPlacement.placeOnce(CUSTOMER_ID);

            // THEN the reservation covers the whole cart in ONE call. One call per line would mean a
            // three-line order taking stock three times with no way to undo the first two when the
            // third turned out to be short - Phase 0's all-or-nothing checkout, lost by the act of
            // moving stock behind HTTP.
            ArgumentCaptor<ReservationRequest> reservation = ArgumentCaptor.forClass(ReservationRequest.class);
            verify(inventoryClient).reserve(reservation.capture());
            assertThat(reservation.getValue().lines())
                    .extracting(ReservationRequest.Line::productId, ReservationRequest.Line::quantity)
                    .containsExactly(tuple(1L, 2), tuple(3L, 1));
        }

        @Test
        void placeOnce_always_reservesStockBeforeSavingTheOrder() {
            // GIVEN
            cartIsReturned();
            catalogueIsUp();
            orderSavesSuccessfully();
            cart.addOrIncrease(KEYBOARD.id(), 1);

            // WHEN
            orderPlacement.placeOnce(CUSTOMER_ID);

            // THEN in this order, and the order matters. Both sequences can fail; this one fails in
            // the direction that does not oversell. Saving first would mean an order that exists for
            // stock nobody has - a customer told they bought something - which is far worse to unwind
            // than stock briefly held for nobody.
            var inOrder = org.mockito.Mockito.inOrder(inventoryClient, orderWriter);
            inOrder.verify(inventoryClient).reserve(any());
            inOrder.verify(orderWriter).saveOrder(anyLong(), anyList(), anyList(), anyMap());
        }

        @Test
        void placeOnce_always_passesTheFetchedPricesToTheWriter() {
            // GIVEN
            cartIsReturned();
            catalogueIsUp();
            orderSavesSuccessfully();
            cart.addOrIncrease(KEYBOARD.id(), 2);

            // WHEN
            orderPlacement.placeOnce(CUSTOMER_ID);

            // THEN the price the order is written with is the one read from the catalogue at this
            // moment, and it is handed over as a value. OrderItem copies it into the row, which is
            // why repricing the product a second later cannot rewrite what somebody paid - the
            // duplication is deliberate, and across a service boundary it is the only option.
            ArgumentCaptor<Map<Long, CatalogProduct>> prices = ArgumentCaptor.forClass(Map.class);
            verify(orderWriter).saveOrder(eq(CUSTOMER_ID), anyList(), anyList(), prices.capture());
            assertThat(prices.getValue().get(1L).price()).isEqualByComparingTo("129.99");
        }
    }

    @Nested
    class ThingsTheCartGetsWrong {

        @Test
        void placeOnce_withAnEmptyCart_throwsConflictAndCallsNobody() {
            // GIVEN
            cartIsReturned();

            // WHEN / THEN
            assertThatThrownBy(() -> orderPlacement.placeOnce(CUSTOMER_ID))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Cannot place an order: the cart is empty");

            // AND no network call was made at all - the cheapest failure is the local one
            verify(catalogClient, never()).findAll();
            verify(inventoryClient, never()).reserve(any());
            verify(orderAuditService).recordAttempt(eq(OrderAudit.Outcome.EMPTY_CART), any(), eq(null));
        }

        @Test
        void placeOnce_whenAProductHasBeenDeletedFromTheCatalogue_refusesTheCheckout() {
            // GIVEN a cart line for a product the catalogue no longer lists. Nothing prevents this:
            // there is no foreign key from cart_items to products.
            cartIsReturned();
            catalogueIsUp();
            cart.addOrIncrease(999L, 1);

            // WHEN / THEN the cart page tolerates such a line and prices it at zero; buying it must
            // not. Charging someone zero for something that does not exist is the failure this
            // prevents.
            assertThatThrownBy(() -> orderPlacement.placeOnce(CUSTOMER_ID))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("Product 999 is no longer available");
            verify(inventoryClient, never()).reserve(any());
        }

        @Test
        void placeOnce_whenInventorySays409_reportsItAsAConflictWithoutLeakingTheUpstreamBody() {
            // GIVEN not enough stock, as inventory-service reports it
            cartIsReturned();
            catalogueIsUp();
            cart.addOrIncrease(KEYBOARD.id(), 99);
            willThrow(HttpClientErrorException.create(
                    HttpStatus.CONFLICT, "Conflict", null,
                    "{\"status\":409,\"message\":\"Only 3 unit(s) of product 1 in stock\"}".getBytes(), null))
                    .given(inventoryClient).reserve(any());

            // WHEN / THEN a 409 from there becomes a 409 here - the same answer Phase 0 gave when the
            // stock check was a field comparison. What is deliberately NOT done is forwarding the
            // upstream message verbatim: one service's error text must not become another's API.
            assertThatThrownBy(() -> orderPlacement.placeOnce(CUSTOMER_ID))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("One or more items are no longer available in the quantity requested");

            verify(orderWriter, never()).saveOrder(anyLong(), anyList(), anyList(), anyMap());
            verify(orderAuditService).recordAttempt(
                    eq(OrderAudit.Outcome.INSUFFICIENT_STOCK), any(), eq(null));
        }
    }

    @Nested
    class ThingsTheNetworkGetsWrong {

        @Test
        void placeOnce_whenCatalogServiceIsUnreachable_refusesRatherThanGuessingAPrice() {
            // GIVEN
            cartIsReturned();
            cart.addOrIncrease(KEYBOARD.id(), 1);
            given(catalogClient.findAll()).willThrow(new ResourceAccessException("connection refused"));

            // WHEN / THEN there is no cached price and no fallback, on purpose. Charging somebody
            // from a stale number is worse than telling them to try again, so this is a place where
            // a dependency being down genuinely means this service cannot do its job.
            assertThatThrownBy(() -> orderPlacement.placeOnce(CUSTOMER_ID))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("catalog-service is unavailable");
            verify(inventoryClient, never()).reserve(any());
        }

        @Test
        void placeOnce_whenInventoryServiceIsUnreachable_refusesWithoutBlamingTheCart() {
            // GIVEN
            cartIsReturned();
            catalogueIsUp();
            cart.addOrIncrease(KEYBOARD.id(), 1);
            willThrow(new ResourceAccessException("connection refused"))
                    .given(inventoryClient).reserve(any());

            // WHEN / THEN
            assertThatThrownBy(() -> orderPlacement.placeOnce(CUSTOMER_ID))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("inventory-service is unavailable");
            verify(orderWriter, never()).saveOrder(anyLong(), anyList(), anyList(), anyMap());
        }

        @Test
        void placeOnce_whenInventoryReturns500_doesNotSwallowIt() {
            // GIVEN a genuine server error rather than a refusal
            cartIsReturned();
            catalogueIsUp();
            cart.addOrIncrease(KEYBOARD.id(), 1);
            willThrow(HttpServerErrorException.create(
                    HttpStatus.INTERNAL_SERVER_ERROR, "boom", null, null, null))
                    .given(inventoryClient).reserve(any());

            // WHEN / THEN it propagates as a 500 rather than being dressed up as a 409. A shopper
            // told "your cart is no longer available" when the real answer is "we are broken" will
            // rebuild their cart and hit the same wall.
            assertThatThrownBy(() -> orderPlacement.placeOnce(CUSTOMER_ID))
                    .isInstanceOf(HttpServerErrorException.class);
        }
    }

    @Nested
    class TheGapNoTransactionCovers {

        @Test
        void placeOnce_whenTheOrderFailsAfterStockWasReserved_releasesTheStock() {
            // GIVEN a reservation that succeeded and a database write that then failed
            cartIsReturned();
            catalogueIsUp();
            cart.addOrIncrease(KEYBOARD.id(), 2);
            given(orderWriter.saveOrder(eq(CUSTOMER_ID), anyList(), anyList(), anyMap()))
                    .willThrow(new IllegalStateException("database is gone"));

            // WHEN
            assertThatThrownBy(() -> orderPlacement.placeOnce(CUSTOMER_ID))
                    .isInstanceOf(IllegalStateException.class);

            // THEN the stock is given back - a COMPENSATION, not a rollback. @Transactional covers
            // the database write and can never cover the reservation: a transaction belongs to one
            // connection to one database, and there is no connection here that reaches both.
            ArgumentCaptor<ReservationRequest> released = ArgumentCaptor.forClass(ReservationRequest.class);
            verify(inventoryClient).release(released.capture());
            assertThat(released.getValue().lines())
                    .extracting(ReservationRequest.Line::productId, ReservationRequest.Line::quantity)
                    .containsExactly(tuple(1L, 2));
        }

        @Test
        void placeOnce_whenTheCompensationItselfFails_stillReportsTheOriginalFailure() {
            // GIVEN the worst case: the order failed AND putting the stock back failed too
            cartIsReturned();
            catalogueIsUp();
            cart.addOrIncrease(KEYBOARD.id(), 1);
            given(orderWriter.saveOrder(eq(CUSTOMER_ID), anyList(), anyList(), anyMap()))
                    .willThrow(new IllegalStateException("database is gone"));
            willThrow(new ResourceAccessException("inventory is gone too"))
                    .given(inventoryClient).release(any());

            // WHEN / THEN the caller learns why the checkout failed, not why the cleanup failed.
            // Replacing the original exception would hide the actual problem behind its aftermath.
            assertThatThrownBy(() -> orderPlacement.placeOnce(CUSTOMER_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("database is gone");

            // AND the system is now genuinely inconsistent: stock is reserved for an order that does
            // not exist, and nothing will ever retry. This test asserts the behaviour rather than
            // pretending it is handled - a durable, expiring reservation is the saga of Phase 24.
        }
    }
}
