package com.ecomdemo.cart.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Payload for PUT /api/cart/items/{productId}. The quantity must be positive; removing a line is
 * DELETE, not "update to zero", so that each verb means exactly one thing.
 */
public record UpdateCartItemRequest(

        @NotNull(message = "is required")
        @Positive(message = "must be greater than zero")
        Integer quantity) {
}
