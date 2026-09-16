package com.ecomdemo.order;

import java.math.BigDecimal;
import java.util.List;

import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartItemResponse;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.common.ApiError;
import com.ecomdemo.order.dto.OrderItemResponse;
import com.ecomdemo.order.dto.OrderResponse;
import com.ecomdemo.product.dto.ProductRequest;
import com.ecomdemo.product.dto.ProductResponse;
import com.ecomdemo.support.AbstractPostgresIT;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole checkout, over HTTP, against real PostgreSQL. This replaces the H2-based
 * {@code PlaceOrderFlowTest} from Phase 2: same flow, an engine that matters.
 *
 * <p>It exercises the one path where several aggregates change together - order written, stock
 * reduced, cart emptied, all in one transaction - and does it through a socket, so the HTTP layer,
 * Jackson, the transaction boundary and PostgreSQL are all the real ones.
 *
 * <p>It also quietly covers the Phase 5 migrations and the Phase 6 audit table, because both are
 * built and written by PostgreSQL here rather than by H2 pretending.
 */
class PlaceOrderIT extends AbstractPostgresIT {

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

    private ProductResponse newProduct(String name, String price, int stock) {
        return admin.post().uri("/api/products")
                .body(new ProductRequest(name, "for the order IT", new BigDecimal(price), stock, null))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(ProductResponse.class)
                .returnResult().getResponseBody();
    }

    private ProductResponse getProduct(Long id) {
        return anonymous.get().uri("/api/products/" + id).exchange()
                .expectStatus().isOk()
                .expectBody(ProductResponse.class)
                .returnResult().getResponseBody();
    }

    @Test
    void placeOrder_withItemsInTheCart_createsTheOrderReducesStockAndEmptiesTheCart() {
        // GIVEN two products in the cart
        ProductResponse keyboard = newProduct("IT Order Keyboard " + System.nanoTime(), "129.99", 40);
        ProductResponse monitor = newProduct("IT Order Monitor " + System.nanoTime(), "399.00", 15);
        client.post().uri("/api/cart/items").body(new AddCartItemRequest(keyboard.id(), 2))
                .exchange().expectStatus().isOk();
        client.post().uri("/api/cart/items").body(new AddCartItemRequest(monitor.id(), 1))
                .exchange().expectStatus().isOk();

        // WHEN
        OrderResponse order = client.post().uri("/api/orders")
                .exchange()
                .expectStatus().isCreated()
                .expectBody(OrderResponse.class)
                .returnResult().getResponseBody();

        // THEN the order carries the lines, with the name and price snapshotted at checkout
        assertThat(order).isNotNull();
        assertThat(order.id()).isNotNull();
        assertThat(order.placedAt()).isNotNull();
        assertThat(order.totalAmount()).isEqualByComparingTo("658.98");
        assertThat(order.items())
                .extracting(OrderItemResponse::productName, OrderItemResponse::quantity)
                .containsExactlyInAnyOrder(
                        org.assertj.core.api.Assertions.tuple(keyboard.name(), 2),
                        org.assertj.core.api.Assertions.tuple(monitor.name(), 1));

        // AND stock fell by exactly what was ordered
        assertThat(getProduct(keyboard.id()).stockQuantity()).isEqualTo(38);
        assertThat(getProduct(monitor.id()).stockQuantity()).isEqualTo(14);

        // AND the cart became the order
        assertThat(getCart().items()).isEmpty();

        // AND the order can be read back, and appears in the list
        OrderResponse fetched = client.get().uri("/api/orders/" + order.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrderResponse.class)
                .returnResult().getResponseBody();
        assertThat(fetched).isNotNull();
        assertThat(fetched.totalAmount()).isEqualByComparingTo("658.98");

        List<OrderResponse> orders = client.get().uri("/api/orders")
                .exchange()
                .expectStatus().isOk()
                .expectBody(ORDER_LIST)
                .returnResult().getResponseBody();
        assertThat(orders).isNotNull();
        assertThat(orders).extracting(OrderResponse::id).contains(order.id());
    }

    @Test
    void placeOrder_afterThePriceChanges_keepsWhatWasChargedAtCheckout() {
        // GIVEN an order placed at one price
        ProductResponse product = newProduct("IT Order Repriced " + System.nanoTime(), "100.00", 10);
        client.post().uri("/api/cart/items").body(new AddCartItemRequest(product.id(), 1))
                .exchange().expectStatus().isOk();
        OrderResponse order = client.post().uri("/api/orders")
                .exchange()
                .expectStatus().isCreated()
                .expectBody(OrderResponse.class)
                .returnResult().getResponseBody();
        assertThat(order).isNotNull();

        // WHEN the catalogue is repriced afterwards
        admin.put().uri("/api/products/" + product.id())
                .body(new ProductRequest("Renamed Entirely", "now dearer", new BigDecimal("999.99"), 9, null))
                .exchange().expectStatus().isOk();

        // THEN history is not rewritten: order_items copied the name and price at checkout
        OrderResponse fetched = client.get().uri("/api/orders/" + order.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrderResponse.class)
                .returnResult().getResponseBody();

        assertThat(fetched).isNotNull();
        assertThat(fetched.items().getFirst().unitPrice()).isEqualByComparingTo("100.00");
        assertThat(fetched.items().getFirst().productName()).isEqualTo(product.name());
        assertThat(fetched.totalAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void placeOrder_whenStockRanOutAfterTheCartWasFilled_is409AndChangesNothing() {
        // GIVEN a cart holding five, and stock that drops to one before checkout
        ProductResponse product = newProduct("IT Order Vanishing " + System.nanoTime(), "30.00", 5);
        client.post().uri("/api/cart/items").body(new AddCartItemRequest(product.id(), 5))
                .exchange().expectStatus().isOk();
        admin.put().uri("/api/products/" + product.id())
                .body(new ProductRequest(product.name(), "sold out", new BigDecimal("30.00"), 1, null))
                .exchange().expectStatus().isOk();

        // WHEN
        ApiError error = client.post().uri("/api/orders")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody(ApiError.class)
                .returnResult().getResponseBody();

        // THEN nothing was half-written - the transaction rolled back in PostgreSQL, not in H2
        assertThat(error).isNotNull();
        assertThat(error.message()).contains("Only 1 unit(s)");
        assertThat(getProduct(product.id()).stockQuantity()).isEqualTo(1);
        assertThat(getCart().items()).hasSize(1);
    }

    @Test
    void placeOrder_withAnEmptyCart_is409() {
        // GIVEN the cart emptied by @BeforeEach
        // WHEN / THEN
        ApiError error = client.post().uri("/api/orders")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody(ApiError.class)
                .returnResult().getResponseBody();

        assertThat(error).isNotNull();
        assertThat(error.message()).isEqualTo("Cannot place an order: the cart is empty");
    }
}
