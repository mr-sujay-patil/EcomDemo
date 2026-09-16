package com.ecomdemo.cart.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Payload for POST /api/cart/items. Notice there is no price field - the server owns pricing. */
public record AddCartItemRequest(

        @NotNull(message = "is required")
        Long productId,

        @NotNull(message = "is required")
        @Positive(message = "must be greater than zero")
        Integer quantity) {
}
