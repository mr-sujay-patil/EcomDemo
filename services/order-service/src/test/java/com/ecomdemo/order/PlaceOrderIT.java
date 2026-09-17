package com.ecomdemo.order;

import java.util.List;

import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartItemResponse;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.order.client.ReservationRequest;
import com.ecomdemo.order.dto.OrderItemResponse;
import com.ecomdemo.order.dto.OrderResponse;
import com.ecomdemo.shared.ApiError;
import com.ecomdemo.support.AbstractOrderServiceIT;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The whole checkout, over HTTP, against real PostgreSQL - with the two neighbours stubbed.
 *
 * <p>It exercises the path where several things change together: the order is written, the cart is
 * emptied, an event is published, and stock is reserved somewhere else entirely. The local half is
 * one transaction against a real engine; the remote half is a mock that can be told to succeed or
 * refuse.
 *
 * <p>It also quietly covers the Flyway migration and the audit table, because both are built and
 * written by PostgreSQL here rather than by H2 pretending.
 *
 * <h2>The most valuable test in this class</h2>
 *
 * {@code placeOrder_whenTheOrderCannotBeSaved_releasesTheReservedStock}. Every other test here would
 * have passed before the split too. That one is about the hole the split opened - two databases, no
 * transaction across them - and about the fact that what fills it is a compensating call rather than
 * a rollback.
 */
class PlaceOrderIT extends AbstractOrderServiceIT {

    private static final ParameterizedTypeReference<List<OrderResponse>> ORDER_LIST =
            new ParameterizedTypeReference<>() {
            };

    @BeforeEach
    void emptyTheCart() {
        for (CartItemResponse item : getCart().items()) {
            client.delete().uri("/api/cart/items/" + item.productId()).exchange().expectStatus().isOk();
        }
    }

    private CartResponse getCart() {
        return client.get().uri("/api/cart").exchange()
                .expectStatus().isOk()
                .expectBody(CartResponse.class)
                .returnResult().getResponseBody();
    }

    private void addToCart(Long productId, int quantity) {
        client.post().uri("/api/cart/items").body(new AddCartItemRequest(productId, quantity))
                .exchange().expectStatus().isOk();
    }

    private OrderResponse checkout() {
        return client.post().uri("/api/orders")
                .exchange()
                .expectStatus().isCreated()
                .expectBody(OrderResponse.class)
                .returnResult().getResponseBody();
    }

    @Test
    void placeOrder_withItemsInTheCart_createsTheOrderReservesStockAndEmptiesTheCart() {
        // GIVEN two products in the cart
        addToCart(KEYBOARD.id(), 2);
        addToCart(MOUSE.id(), 1);

        // WHEN
        OrderResponse order = checkout();

        // THEN the order carries the lines, with the name and price snapshotted at checkout
        assertThat(order).isNotNull();
        assertThat(order.id()).isNotNull();
        assertThat(order.placedAt()).isNotNull();
        assertThat(order.totalAmount()).isEqualByComparingTo("309.48");
        assertThat(order.items())
                .extracting(OrderItemResponse::productName, OrderItemResponse::quantity)
                .containsExactlyInAnyOrder(
                        tuple("Mechanical Keyboard", 2),
                        tuple("Wireless Mouse", 1));

        // AND the stock was taken in ONE reservation covering the whole cart
        ArgumentCaptor<ReservationRequest> reservation = ArgumentCaptor.forClass(ReservationRequest.class);
        verify(inventoryClient).reserve(reservation.capture());
        assertThat(reservation.getValue().lines()).hasSize(2);

        // AND the cart became the order
        assertThat(getCart().items()).isEmpty();
    }

    @Test
    void placeOrder_always_snapshotsThePriceItChargedRatherThanReadingItBack() {
        // GIVEN
        addToCart(KEYBOARD.id(), 1);
        OrderResponse order = checkout();

        // WHEN the catalogue is repriced afterwards - which, being another service's data, could
        // happen at any moment and without this service hearing about it
        given(catalogClient.findAll()).willReturn(List.of(
                new com.ecomdemo.order.client.CatalogProduct(
                        KEYBOARD.id(), "Renamed Keyboard", new java.math.BigDecimal("999.99"))));

        // THEN reading the order back still shows what was charged. The row holds its own copy, and
        // nothing resolves product_id to anything - which is the only way a historical record can
        // survive when the authoritative data is in somebody else's database.
        OrderResponse reread = client.get().uri("/api/orders/" + order.id())
                .exchange().expectStatus().isOk()
                .expectBody(OrderResponse.class).returnResult().getResponseBody();

        assertThat(reread.items().getFirst().unitPrice()).isEqualByComparingTo("129.99");
        assertThat(reread.items().getFirst().productName()).isEqualTo("Mechanical Keyboard");
    }

    @Test
    void placeOrder_withAnEmptyCart_is409AndReservesNothing() {
        // WHEN / THEN
        ApiError error = client.post().uri("/api/orders")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody(ApiError.class)
                .returnResult().getResponseBody();

        assertThat(error).isNotNull();
        assertThat(error.message()).isEqualTo("Cannot place an order: the cart is empty");

        // The cheapest failure is the local one: no network call was made at all.
        verify(inventoryClient, never()).reserve(any());
    }

    @Test
    void placeOrder_whenInventoryRefuses_is409AndTheCartSurvives() {
        // GIVEN a cart, and an inventory-service that says there is not enough
        addToCart(KEYBOARD.id(), 5);
        willThrow(HttpClientErrorException.create(HttpStatus.CONFLICT, "Conflict", null, null, null))
                .given(inventoryClient).reserve(any());

        // WHEN / THEN
        client.post().uri("/api/orders").exchange().expectStatus().isEqualTo(409);

        // AND the shopper keeps their cart, so they can change it and try again rather than starting
        // over. A failed checkout that also emptied the cart would be two problems.
        assertThat(getCart().items()).singleElement()
                .satisfies(item -> assertThat(item.quantity()).isEqualTo(5));
    }

    @Test
    void placeOrder_whenTheOrderCannotBeSaved_releasesTheReservedStock() {
        // GIVEN a cart whose reservation will succeed and whose order will not.
        //
        // The failure is provoked by making the SECOND catalogue call return a product the writer
        // cannot price... which it cannot, so instead: a line for a product that exists when the
        // cart is priced and whose reservation succeeds, followed by a database that rejects the
        // write. The simplest honest way to get there over HTTP is a quantity that overflows the
        // column, which fails at flush - after the reservation.
        addToCart(KEYBOARD.id(), 1);
        client.put().uri("/api/cart/items/" + KEYBOARD.id())
                .body(new com.ecomdemo.cart.dto.UpdateCartItemRequest(Integer.MAX_VALUE))
                .exchange().expectStatus().isOk();

        // WHEN checkout is attempted. total_amount is NUMERIC(12,2); 129.99 x Integer.MAX_VALUE
        // overflows it, so the INSERT fails at flush - inside OrderWriter's transaction, and after
        // inventoryClient.reserve has already been called and returned.
        client.post().uri("/api/orders").exchange().expectStatus().is5xxServerError();

        // THEN the stock was reserved AND released. That release is a compensation, not a rollback:
        // @Transactional undid the order row, and could do nothing whatever about the reservation in
        // another service's database. If this HTTP call had failed too, the stock would simply have
        // stayed gone - which is what Phase 24's saga exists to make impossible.
        verify(inventoryClient).reserve(any());
        verify(inventoryClient).release(any());
    }

    @Test
    void listOrders_afterCheckout_returnsTheOrderForItsOwnerOnly() {
        // GIVEN an order placed by this test's customer
        addToCart(MOUSE.id(), 1);
        Long orderId = checkout().id();

        // WHEN this customer lists their orders
        List<OrderResponse> mine = client.get().uri("/api/orders")
                .exchange().expectStatus().isOk()
                .expectBody(ORDER_LIST).returnResult().getResponseBody();

        // THEN it is there
        assertThat(mine).extracting(OrderResponse::id).contains(orderId);

        // AND somebody else asking for it by id gets 404, not 403. 403 confirms it exists, which
        // turns sequential ids into an enumeration tool - and the query scopes by owner in the WHERE
        // clause, so the row is never loaded at all.
        var somebodyElse = clientFor(com.ecomdemo.shared.testsupport.TestTokens.issueCustomer(999_999L));
        somebodyElse.get().uri("/api/orders/" + orderId).exchange().expectStatus().isNotFound();
    }
}
