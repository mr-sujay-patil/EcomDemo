package com.ecomdemo.cart.dto;

import java.math.BigDecimal;

import com.ecomdemo.cart.CartItem;

/** One rendered cart line, with the line total pre-calculated for the client. */
public record CartItemResponse(
        Long productId,
        String productName,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal lineTotal) {

    public static CartItemResponse from(CartItem item) {
        return new CartItemResponse(
                item.getProduct().getId(),
                item.getProduct().getName(),
                item.getProduct().getPrice(),
                item.getQuantity(),
                item.lineTotal());
    }
}
