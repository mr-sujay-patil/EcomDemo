package com.ecomdemo.order.client;

import java.util.List;

/**
 * The reservation payload, as this service sends it.
 *
 * <p>A second declaration of a shape inventory-service also declares - deliberately, and for the same
 * reason as {@link CatalogProduct}. The two records are kept in step by the JSON field names and by a
 * test on each side, not by a shared jar. Duplicating twenty lines is the price of being able to
 * deploy either service without the other.
 *
 * @param orderReference a label for this reservation in both services' logs. It reserves nothing by
 *                       itself and inventory-service does not record it: there is no reservation to
 *                       look up, retry idempotently or expire. See {@link InventoryClient#release}.
 */
public record ReservationRequest(String orderReference, List<Line> lines) {

    public record Line(Long productId, Integer quantity) {
    }
}
