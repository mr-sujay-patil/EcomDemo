package com.ecomdemo.product.dto;

import java.math.BigDecimal;

import com.ecomdemo.product.Product;

/**
 * Outgoing view of a product. The API contract lives here, decoupled from the entity: renaming a
 * column or adding a lazy association later cannot silently change the JSON clients depend on.
 *
 * <p><strong>{@code stockQuantity} is gone from this response</strong>, and it is the most visible
 * consequence of the split for anyone calling the API. Availability is inventory-service's answer to
 * give; ask {@code GET /api/stock/{productId}} for it. Phase 21's gateway can compose the two into
 * one response - aggregation is listed there as a gateway responsibility - but doing it here would
 * mean the catalogue could not be served at all while inventory-service was down, which is a poor
 * trade for a page that mostly shows names and prices.
 *
 * <p>Note also that this record is deliberately NOT shared with order-service, which needs a
 * product's name and price at checkout. It declares its own three-field view instead. Two services
 * agreeing on a JSON shape is a contract; two services compiling against the same class is a
 * dependency, and it would mean adding a field here could not be done without redeploying both.
 */
public record ProductResponse(
        Long id,
        String name,
        String description,
        BigDecimal price,
        String category) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getCategory());
    }
}
