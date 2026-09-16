package com.ecomdemo.product.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Incoming payload for creating or updating a product.
 *
 * <p>Note what is absent: {@code id}. A client must never choose a primary key. Using a dedicated
 * request record rather than the entity is what makes that guarantee structural instead of a rule
 * somebody has to remember.
 *
 * <p>Also absent since Phase 20: {@code stockQuantity}. Creating a sellable product is now two calls
 * to two services - POST here, then PUT to inventory-service - and there is no transaction spanning
 * them. An administrator can create a product and then fail to set its stock, leaving a listed item
 * nobody can buy. That is not an oversight to be fixed with a cleverer API; it is what "no
 * cross-service transactions" means, and the honest answers to it are a saga (Phase 24) or accepting
 * that the intermediate state is visible and correctable.
 */
public record ProductRequest(

        @NotBlank(message = "must not be blank")
        @Size(max = 255, message = "must be at most 255 characters")
        String name,

        @Size(max = 1000, message = "must be at most 1000 characters")
        String description,

        @NotNull(message = "is required")
        @Positive(message = "must be greater than zero")
        BigDecimal price,

        /*
         * Optional, matching the nullable column V3 added. No @NotBlank: a client that has never
         * heard of categories must keep working exactly as it did before, which is the whole point
         * of shipping the column nullable.
         */
        @Size(max = 100, message = "must be at most 100 characters")
        String category) {
}
