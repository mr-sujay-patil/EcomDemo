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
