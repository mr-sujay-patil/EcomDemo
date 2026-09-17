package com.ecomdemo.cart.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import com.ecomdemo.cart.Cart;
import com.ecomdemo.order.client.CatalogProduct;

/**
 * The whole cart as the client sees it. {@code totalItems} and {@code total} are both derived
 * server-side; the client never sends or is trusted with a monetary value.
 */
public record CartResponse(
        Long cartId,
        List<CartItemResponse> items,
        int totalItems,
        BigDecimal total) {

    /**
     * The view of a cart that does not exist yet.
     *
     * <p>A customer who has never added anything has no row in {@code carts}, and reading their cart
     * must not create one - a GET that writes turns every visit into a database insert and leaves a
     * row behind for everybody who ever looked. {@code cartId} is null because there genuinely is no
     * cart; it appears the moment something is added.
     */
    public static CartResponse empty() {
        return new CartResponse(null, List.of(), 0, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * Renders a cart at the prices supplied.
     *
     * <p>The prices are a parameter rather than something the cart can reach, which is the whole shape
     * of the change in Phase 20: {@code Cart.total()} used to walk the lines and read
     * {@code product.getPrice()} through an association. There is no association now, so the caller
     * fetches the catalogue once - one HTTP call per cart, not one per line - and the total is summed
     * here from what came back.
     *
     * @param prices product id to product, for every line that could be priced. A line missing from
     *               this map renders as unavailable at zero; see {@link CartItemResponse}.
     */
    public static CartResponse from(Cart cart, Map<Long, CatalogProduct> prices) {
        List<CartItemResponse> items = cart.getItems().stream()
                .map(item -> CartItemResponse.from(item, prices.get(item.getProductId())))
                .toList();
        int totalItems = items.stream().mapToInt(CartItemResponse::quantity).sum();
        BigDecimal total = items.stream()
                .map(CartItemResponse::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CartResponse(
                cart.getId(),
                items,
                totalItems,
                total.setScale(2, RoundingMode.HALF_UP));
    }
}
