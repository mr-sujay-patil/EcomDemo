package com.ecomdemo.cart;

import java.math.BigDecimal;

import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartItemResponse;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.cart.dto.UpdateCartItemRequest;
import com.ecomdemo.common.ApiError;
import com.ecomdemo.product.dto.ProductRequest;
import com.ecomdemo.product.dto.ProductResponse;
import com.ecomdemo.support.AbstractPostgresIT;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cart, end to end over HTTP against real PostgreSQL.
 *
 * <p>The cart is the one shared row until users arrive in Phase 8, so every test here starts by
 * emptying it. Integration tests commit - there is no transaction rolled back around them - and they
 * all share one container, so state left behind is state the next test inherits.
 *
 * <p>What a real database adds over the slice tests: the totals are computed from values that made a
 * round trip through {@code numeric(12,2)} columns, and the cascade and {@code orphanRemoval} on
 * {@code Cart.items} are executed by PostgreSQL rather than described to H2.
 */
class CartFlowIT extends AbstractPostgresIT {

    @BeforeEach
    void emptyTheCart() {
        CartResponse cart = getCart();
        for (CartItemResponse item : cart.items()) {
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
        return client.post().uri("/api/products")
                .body(new ProductRequest(name, "for the cart IT", new BigDecimal(price), stock, null))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(ProductResponse.class)
                .returnResult().getResponseBody();
    }

    @Test
    void addItem_thenReadCart_showsTheLineAndAServerCalculatedTotal() {
        // GIVEN
        ProductResponse product = newProduct("IT Cart Keyboard " + System.nanoTime(), "129.99", 40);

        // WHEN
        CartResponse cart = client.post().uri("/api/cart/items")
                .body(new AddCartItemRequest(product.id(), 2))
                .exchange()
                .expectStatus().isOk()
                .expectBody(CartResponse.class)
                .returnResult().getResponseBody();

        // THEN the total is the server's arithmetic on the current price, never the client's
        assertThat(cart).isNotNull();
        assertThat(cart.items()).singleElement().satisfies(item -> {
            assertThat(item.productName()).isEqualTo(product.name());
            assertThat(item.quantity()).isEqualTo(2);
            assertThat(item.lineTotal()).isEqualByComparingTo("259.98");
        });
        assertThat(cart.total()).isEqualByComparingTo("259.98");
        assertThat(cart.totalItems()).isEqualTo(2);
    }

    @Test
    void addItem_twiceForTheSameProduct_increasesTheQuantityInsteadOfAddingALine() {
        // GIVEN
        ProductResponse product = newProduct("IT Cart Mouse " + System.nanoTime(), "49.50", 120);

        // WHEN the same product is added twice
        client.post().uri("/api/cart/items").body(new AddCartItemRequest(product.id(), 1))
                .exchange().expectStatus().isOk();
        CartResponse cart = client.post().uri("/api/cart/items")
                .body(new AddCartItemRequest(product.id(), 3))
                .exchange()
                .expectStatus().isOk()
                .expectBody(CartResponse.class)
                .returnResult().getResponseBody();

        // THEN there is still one line, holding four
        assertThat(cart).isNotNull();
        assertThat(cart.items()).singleElement()
                .satisfies(item -> assertThat(item.quantity()).isEqualTo(4));
        assertThat(cart.total()).isEqualByComparingTo("198.00");
    }

    @Test
    void updateItemQuantity_recalculatesTheTotal() {
        // GIVEN
        ProductResponse product = newProduct("IT Cart Hub " + System.nanoTime(), "59.95", 85);
        client.post().uri("/api/cart/items").body(new AddCartItemRequest(product.id(), 1))
                .exchange().expectStatus().isOk();

        // WHEN
        CartResponse cart = client.put().uri("/api/cart/items/" + product.id())
                .body(new UpdateCartItemRequest(3))
                .exchange()
                .expectStatus().isOk()
                .expectBody(CartResponse.class)
                .returnResult().getResponseBody();

        // THEN
        assertThat(cart).isNotNull();
        assertThat(cart.total()).isEqualByComparingTo("179.85");
    }

    @Test
    void removeItem_emptiesTheLineAndTheOrphanIsDeleted() {
        // GIVEN
        ProductResponse product = newProduct("IT Cart Stand " + System.nanoTime(), "39.00", 200);
        client.post().uri("/api/cart/items").body(new AddCartItemRequest(product.id(), 2))
                .exchange().expectStatus().isOk();

        // WHEN
        CartResponse cart = client.delete().uri("/api/cart/items/" + product.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(CartResponse.class)
                .returnResult().getResponseBody();

        // THEN the line is gone and the total is back to zero. orphanRemoval turned the removal from
        // the collection into a real DELETE, which PostgreSQL executed.
        assertThat(cart).isNotNull();
        assertThat(cart.items()).isEmpty();
        assertThat(cart.total()).isEqualByComparingTo("0.00");
    }

    @Test
    void addItem_beyondAvailableStock_is409AndLeavesTheCartAlone() {
        // GIVEN a product with three in stock
        ProductResponse product = newProduct("IT Cart Scarce " + System.nanoTime(), "10.00", 3);

        // WHEN four are requested
        ApiError error = client.post().uri("/api/cart/items")
                .body(new AddCartItemRequest(product.id(), 4))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody(ApiError.class)
                .returnResult().getResponseBody();

        // THEN the request was valid, the state forbade it
        assertThat(error).isNotNull();
        assertThat(error.message()).contains("Only 3 unit(s)");
        assertThat(getCart().items()).isEmpty();
    }

    @Test
    void removeItem_thatIsNotInTheCart_is404() {
        // GIVEN a product that exists but was never added
        ProductResponse product = newProduct("IT Cart Absent " + System.nanoTime(), "10.00", 5);

        // WHEN / THEN
        client.delete().uri("/api/cart/items/" + product.id())
                .exchange()
                .expectStatus().isNotFound();
    }
}
