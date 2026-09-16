package com.ecomdemo.cart.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import com.ecomdemo.cart.Cart;

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

    public static CartResponse from(Cart cart) {
        List<CartItemResponse> items = cart.getItems().stream()
                .map(CartItemResponse::from)
                .toList();
        int totalItems = items.stream().mapToInt(CartItemResponse::quantity).sum();
        return new CartResponse(
                cart.getId(),
                items,
                totalItems,
                cart.total().setScale(2, RoundingMode.HALF_UP));
    }
}
