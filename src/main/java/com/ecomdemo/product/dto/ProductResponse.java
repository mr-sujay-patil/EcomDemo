package com.ecomdemo.product.dto;

import java.math.BigDecimal;

import com.ecomdemo.product.Product;

/**
 * Outgoing view of a product. The API contract lives here, decoupled from the entity: renaming a
 * column or adding a lazy association later cannot silently change the JSON clients depend on.
 */
public record ProductResponse(
        Long id,
        String name,
        String description,
        BigDecimal price,
        int stockQuantity,
        String category) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getStockQuantity(),
                product.getCategory());
    }
}
