package com.ecomdemo.order;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.order.dto.OrderResponse;
import com.ecomdemo.product.dto.ProductResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one integration test of this phase.
 *
 * <p>{@code @SpringBootTest(webEnvironment = RANDOM_PORT)} starts the whole application - the Spring
 * context, Hibernate, the seeded H2 database and a real Tomcat on a free port - so this exercises
 * the same code path as the curl flow in the README, HTTP layer included. A random port is used so
 * the test cannot collide with a locally running instance on 8080.
 *
 * <p>{@link RestTestClient} is Spring Framework 7's synchronous test client, bound here to the live
 * server rather than to MockMvc. It ships with spring-test, so no extra dependency is needed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlaceOrderFlowTest {

    private static final ParameterizedTypeReference<List<ProductResponse>> PRODUCT_LIST =
            new ParameterizedTypeReference<>() {
            };

    @LocalServerPort
    private int port;

    private RestTestClient client;

    @BeforeEach
    void bindToRunningServer() {
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void placeOrder_withItemsInTheCart_createsOrderReducesStockAndEmptiesCart() {
        // GIVEN the catalogue seeded by data.sql
        List<ProductResponse> products = client.get().uri("/api/products")
                .exchange()
                .expectStatus().isOk()
                .expectBody(PRODUCT_LIST)
                .returnResult().getResponseBody();

        assertThat(products).hasSize(10);
        ProductResponse keyboard = products.getFirst();
        int stockBefore = keyboard.stockQuantity();
        int orderedQuantity = 3;
        BigDecimal expectedTotal = keyboard.price().multiply(BigDecimal.valueOf(orderedQuantity));

        // WHEN the product is added to the shared cart
        CartResponse cart = client.post().uri("/api/cart/items")
                .body(Map.of("productId", keyboard.id(), "quantity", orderedQuantity))
                .exchange()
                .expectStatus().isOk()
                .expectBody(CartResponse.class)
                .returnResult().getResponseBody();

        // THEN the total is calculated by the server; the client never sent a price
        assertThat(cart.items()).hasSize(1);
        assertThat(cart.totalItems()).isEqualTo(orderedQuantity);
        assertThat(cart.total()).isEqualByComparingTo(expectedTotal);

        // WHEN the order is placed
        OrderResponse order = client.post().uri("/api/orders")
                .exchange()
                .expectStatus().isCreated()
                .expectBody(OrderResponse.class)
                .returnResult().getResponseBody();

        // THEN the product name and price are snapshotted onto the order line
        assertThat(order.id()).isNotNull();
        assertThat(order.placedAt()).isNotNull();
        assertThat(order.totalAmount()).isEqualByComparingTo(expectedTotal);
        assertThat(order.items()).singleElement().satisfies(item -> {
            assertThat(item.productId()).isEqualTo(keyboard.id());
            assertThat(item.productName()).isEqualTo(keyboard.name());
            assertThat(item.unitPrice()).isEqualByComparingTo(keyboard.price());
            assertThat(item.quantity()).isEqualTo(orderedQuantity);
            assertThat(item.lineTotal()).isEqualByComparingTo(expectedTotal);
        });

        // AND the order can be read back by id
        client.get().uri("/api/orders/" + order.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrderResponse.class)
                .value(fetched -> {
                    assertThat(fetched.id()).isEqualTo(order.id());
                    assertThat(fetched.totalAmount()).isEqualByComparingTo(expectedTotal);
                });

        // AND stock has fallen by exactly the quantity ordered
        client.get().uri("/api/products/" + keyboard.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductResponse.class)
                .value(after -> assertThat(after.stockQuantity()).isEqualTo(stockBefore - orderedQuantity));

        // AND the cart has been emptied
        client.get().uri("/api/cart")
                .exchange()
                .expectStatus().isOk()
                .expectBody(CartResponse.class)
                .value(emptied -> {
                    assertThat(emptied.items()).isEmpty();
                    assertThat(emptied.totalItems()).isZero();
                    assertThat(emptied.total()).isEqualByComparingTo(BigDecimal.ZERO);
                });

        // AND ordering again from the empty cart is a 409, in the shared error shape
        client.post().uri("/api/orders")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.status").isEqualTo(409)
                .jsonPath("$.message").isEqualTo("Cannot place an order: the cart is empty");
    }
}
