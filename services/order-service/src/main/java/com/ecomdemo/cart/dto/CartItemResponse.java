package com.ecomdemo.cart.dto;

import java.math.BigDecimal;

import com.ecomdemo.cart.CartItem;
import com.ecomdemo.order.client.CatalogProduct;

/**
 * One rendered cart line, with the line total pre-calculated for the client.
 *
 * <p>The name and price no longer come off the entity - a cart line holds only a product id now - so
 * they arrive as a second argument, fetched from catalog-service for the whole cart at once.
 *
 * <p>{@code product} may be null, for a line whose product has been deleted from the catalogue or
 * for any line at all when catalog-service is unreachable. The line is still rendered, named as
 * unavailable and priced at zero, rather than being dropped or breaking the page. Checkout refuses
 * such a line, which is where it genuinely matters.
 */
public record CartItemResponse(
        Long productId,
        String productName,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal lineTotal) {

    private static final String UNAVAILABLE = "(unavailable)";

    public static CartItemResponse from(CartItem item, CatalogProduct product) {
        BigDecimal unitPrice = product == null ? BigDecimal.ZERO : product.price();
        return new CartItemResponse(
                item.getProductId(),
                product == null ? UNAVAILABLE : product.name(),
                unitPrice,
                item.getQuantity(),
                unitPrice.multiply(BigDecimal.valueOf(item.getQuantity())));
    }
}
