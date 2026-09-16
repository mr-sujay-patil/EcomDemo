package com.ecomdemo.cart;

import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartItemResponse;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.cart.dto.UpdateCartItemRequest;
import com.ecomdemo.support.AbstractOrderServiceIT;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * The cart, end to end over HTTP against real PostgreSQL - with catalog-service stubbed.
 *
 * <h2>What this test can and cannot say now</h2>
 *
 * It used to create its own products through {@code POST /api/products}, because that endpoint was in
 * the same application. It is in catalog-service now, so the products these tests shop for are
 * whatever {@code AbstractOrderServiceIT} tells the stubbed {@code CatalogClient} to return.
 *
 * <p>What survives, and is still worth running against a real database: the cascade and
 * {@code orphanRemoval} on {@code Cart.items} are executed by PostgreSQL rather than described to H2,
 * the totals are computed from values that made a round trip through {@code numeric(12,2)} columns,
 * and the whole request goes through Tomcat, the filter chain, Jackson and the controller advice.
 *
 * <p>What is gone is the test for adding more than is in stock. That rule is inventory-service's now
 * and is applied at checkout rather than at add-to-cart - see {@code CartServiceTest} for the
 * reasoning, and inventory-service's {@code StockApiIT} for the rule itself. A test asserting the old
 * behaviour here would be asserting a check that was deliberately removed.
 *
 * <p>Every test empties the cart first: integration tests commit, there is no transaction rolled back
 * around them, and they all share one container.
 */
class CartFlowIT extends AbstractOrderServiceIT {

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

    private CartResponse addToCart(Long productId, int quantity) {
        return client.post().uri("/api/cart/items")
                .body(new AddCartItemRequest(productId, quantity))
                .exchange()
                .expectStatus().isOk()
                .expectBody(CartResponse.class)
                .returnResult().getResponseBody();
    }

    @Test
    void addItem_thenReadCart_showsTheLineAndAServerCalculatedTotal() {
        // WHEN - 2 x 129.99
        CartResponse cart = addToCart(KEYBOARD.id(), 2);

        // THEN the total is the server's arithmetic on the price catalog-service reported, never the
        // client's. The price crossed a process boundary to get here, which is new; that it is never
        // taken from the request body is not.
        assertThat(cart).isNotNull();
        assertThat(cart.items()).singleElement().satisfies(item -> {
            assertThat(item.productName()).isEqualTo(KEYBOARD.name());
            assertThat(item.quantity()).isEqualTo(2);
            assertThat(item.lineTotal()).isEqualByComparingTo("259.98");
        });
        assertThat(cart.total()).isEqualByComparingTo("259.98");
        assertThat(cart.totalItems()).isEqualTo(2);
    }

    @Test
    void addItem_twiceForTheSameProduct_increasesTheQuantityInsteadOfAddingALine() {
        // WHEN the same product is added twice
        addToCart(MOUSE.id(), 1);
        CartResponse cart = addToCart(MOUSE.id(), 3);

        // THEN there is still one line, holding four
        assertThat(cart).isNotNull();
        assertThat(cart.items()).singleElement()
                .satisfies(item -> assertThat(item.quantity()).isEqualTo(4));
        assertThat(cart.total()).isEqualByComparingTo("198.00");
    }

    @Test
    void updateItemQuantity_recalculatesTheTotal() {
        // GIVEN
        addToCart(MOUSE.id(), 1);

        // WHEN
        CartResponse cart = client.put().uri("/api/cart/items/" + MOUSE.id())
                .body(new UpdateCartItemRequest(3))
                .exchange()
                .expectStatus().isOk()
                .expectBody(CartResponse.class)
                .returnResult().getResponseBody();

        // THEN - 3 x 49.50
        assertThat(cart).isNotNull();
        assertThat(cart.total()).isEqualByComparingTo("148.50");
    }

    @Test
    void removeItem_emptiesTheLineAndTheOrphanIsDeleted() {
        // GIVEN
        addToCart(KEYBOARD.id(), 2);

        // WHEN
        CartResponse cart = client.delete().uri("/api/cart/items/" + KEYBOARD.id())
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
    void addItem_forAProductTheCatalogueDoesNotHave_is404() {
        // GIVEN catalog-service answering 404 for an id nobody has
        given(catalogClient.findById(9999L))
                .willThrow(HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND, "Not Found", null, null, null));

        // WHEN / THEN the upstream 404 becomes this service's own 404, in the shared error shape
        client.post().uri("/api/cart/items")
                .body(new AddCartItemRequest(9999L, 1))
                .exchange()
                .expectStatus().isNotFound();

        assertThat(getCart().items()).isEmpty();
    }

    @Test
    void removeItem_thatIsNotInTheCart_is404() {
        client.delete().uri("/api/cart/items/" + KEYBOARD.id())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void readCart_withNoToken_is401() {
        // Every endpoint in this service belongs to somebody; there is no anonymous view of a cart.
        anonymous.get().uri("/api/cart").exchange().expectStatus().isUnauthorized();
    }
}
