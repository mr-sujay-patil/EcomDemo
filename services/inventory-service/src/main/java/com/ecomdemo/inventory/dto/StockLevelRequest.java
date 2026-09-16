package com.ecomdemo.inventory.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * An administrator setting a product's stock to an absolute figure.
 *
 * <p>Absolute rather than a delta ("add 20"), because this endpoint is not idempotent if it is a
 * delta: a client that retries after a timeout it did not cause would add twice. Setting a value is
 * safe to repeat, which matters much more now that the caller is on the far side of a network.
 */
public record StockLevelRequest(

        @NotNull(message = "is required")
        @PositiveOrZero(message = "must not be negative")
        Integer quantity) {
}
