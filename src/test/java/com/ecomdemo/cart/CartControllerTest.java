package com.ecomdemo.cart;

import java.math.BigDecimal;
import java.util.List;

import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartItemResponse;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.cart.dto.UpdateCartItemRequest;
import com.ecomdemo.common.ConflictException;
import com.ecomdemo.common.NotFoundException;

import com.ecomdemo.support.WithMockCustomer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import com.ecomdemo.common.ApiErrorResponder;
import com.ecomdemo.support.SecurityMockMvcCustomizer;
import com.ecomdemo.common.WebSecurityConfiguration;

import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.BIG_DECIMAL;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Slice tests for {@link CartController} - the HTTP contract of the cart, with the service mocked. */
@WebMvcTest(CartController.class)
/*
 * A @WebMvcTest slice loads controllers, not arbitrary @Configuration classes - so without this the
 * real rules never load, Boot's default security applies instead, and @AuthenticationPrincipal is
 * not even resolved (Spring MVC falls back to treating SecurityUser as a model attribute and tries
 * to construct one). Importing them means these tests exercise the authorization rules that ship.
 */
@Import({WebSecurityConfiguration.class, ApiErrorResponder.class, SecurityMockMvcCustomizer.class})
class CartControllerTest {

    /** Matches the id in @WithMockCustomer, so the stubs and the principal agree. */
    private static final Long CUSTOMER_ID = 42L;


    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private CartService cartService;

    private static CartResponse cartWithOneKeyboard() {
        return new CartResponse(1L,
                List.of(new CartItemResponse(1L, "Mechanical Keyboard",
                        new BigDecimal("129.99"), 2, new BigDecimal("259.98"))),
                2,
                new BigDecimal("259.98"));
    }

    @Test
    @WithMockCustomer(id = 42L)
    void view_whenTheCartHasItems_returns200WithTheServerCalculatedTotal() {
        // GIVEN
        given(cartService.getCart(CUSTOMER_ID)).willReturn(cartWithOneKeyboard());

        // WHEN / THEN
        assertThat(mvc.get().uri("/api/cart"))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.totalItems", n -> assertThat(n).isEqualTo(2))
                .hasPathSatisfying("$.total",
                        total -> assertThat(total).convertTo(BIG_DECIMAL).isEqualByComparingTo("259.98"))
                .hasPathSatisfying("$.items[0].productName",
                        name -> assertThat(name).isEqualTo("Mechanical Keyboard"));
    }

    @Test
    @WithMockCustomer(id = 42L)
    void addItem_withAValidBody_returns200WithTheUpdatedCart() {
        // GIVEN
        given(cartService.addItem(eq(CUSTOMER_ID), any(AddCartItemRequest.class))).willReturn(cartWithOneKeyboard());

        // WHEN / THEN - a mutation returns the whole cart, so the client need not re-fetch
        assertThat(mvc.post().uri("/api/cart/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"productId":1,"quantity":2}"""))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.items[0].quantity", q -> assertThat(q).isEqualTo(2));
    }

    @Test
    @WithMockCustomer(id = 42L)
    void addItem_withAZeroQuantity_returns400() {
        // WHEN / THEN - @Positive rejects it before the controller body runs
        assertThat(mvc.post().uri("/api/cart/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"productId":1,"quantity":0}"""))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.message").asString().contains("quantity must be greater than zero");

        verify(cartService, never()).addItem(any(), any());
    }

    @Test
    @WithMockCustomer(id = 42L)
    void addItem_withAMissingProductId_returns400() {
        // WHEN / THEN
        assertThat(mvc.post().uri("/api/cart/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"quantity":2}"""))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.message").asString().contains("productId is required");
    }

    @Test
    @WithMockCustomer(id = 42L)
    void addItem_whenTheProductDoesNotExist_returns404() {
        // GIVEN
        given(cartService.addItem(eq(CUSTOMER_ID), any()))
                .willThrow(new NotFoundException("Product 9999 not found"));

        // WHEN / THEN
        assertThat(mvc.post().uri("/api/cart/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"productId":9999,"quantity":1}"""))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .hasPathSatisfying("$.status", s -> assertThat(s).isEqualTo(404));
    }

    @Test
    @WithMockCustomer(id = 42L)
    void addItem_whenStockIsInsufficient_returns409() {
        // GIVEN
        given(cartService.addItem(eq(CUSTOMER_ID), any()))
                .willThrow(new ConflictException("Only 40 unit(s) of 'Mechanical Keyboard' in stock, requested 99999"));

        // WHEN / THEN - 409, not 400: the request is well-formed, the state forbids it
        assertThat(mvc.post().uri("/api/cart/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"productId":1,"quantity":99999}"""))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .hasPathSatisfying("$.status", s -> assertThat(s).isEqualTo(409))
                .hasPathSatisfying("$.message",
                        m -> assertThat(m).asString().contains("Only 40 unit(s)"));
    }

    @Test
    @WithMockCustomer(id = 42L)
    void updateItem_withAValidQuantity_returns200() {
        // GIVEN
        given(cartService.updateItemQuantity(eq(CUSTOMER_ID), eq(1L), any(UpdateCartItemRequest.class)))
                .willReturn(cartWithOneKeyboard());

        // WHEN / THEN
        assertThat(mvc.put().uri("/api/cart/items/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"quantity":2}"""))
                .hasStatus(HttpStatus.OK);
    }

    @Test
    @WithMockCustomer(id = 42L)
    void updateItem_whenTheLineIsNotInTheCart_returns404() {
        // GIVEN
        given(cartService.updateItemQuantity(eq(CUSTOMER_ID), eq(42L), any()))
                .willThrow(new NotFoundException("Product 42 is not in the cart"));

        // WHEN / THEN
        assertThat(mvc.put().uri("/api/cart/items/42")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"quantity":2}"""))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .extractingPath("$.message").isEqualTo("Product 42 is not in the cart");
    }

    @Test
    @WithMockCustomer(id = 42L)
    void removeItem_whenTheLineExists_returns200WithTheRemainingCart() {
        // GIVEN
        given(cartService.removeItem(CUSTOMER_ID, 1L)).willReturn(new CartResponse(1L, List.of(), 0, BigDecimal.ZERO));

        // WHEN / THEN
        assertThat(mvc.delete().uri("/api/cart/items/1"))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.items", items -> assertThat(items).asArray().isEmpty());
    }
}
