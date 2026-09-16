package com.ecomdemo.product.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Incoming payload for creating or updating a product.
 *
 * <p>Note what is absent: {@code id}. A client must never choose a primary key. Using a dedicated
 * request record rather than the entity is what makes that guarantee structural instead of a rule
 * somebody has to remember.
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

        @NotNull(message = "is required")
        @PositiveOrZero(message = "must not be negative")
        Integer stockQuantity) {
}
