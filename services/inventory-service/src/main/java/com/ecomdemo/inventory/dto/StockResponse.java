package com.ecomdemo.inventory.dto;

import com.ecomdemo.inventory.StockLevel;

/**
 * How many of one product are available.
 *
 * <p>{@code quantity} is a number at a moment, and callers should treat it that way. By the time this
 * response reaches a browser another shopper may have taken the last one - which was equally true in
 * the monolith and is merely more obvious now that the answer travelled over a network. Nothing may
 * be reserved on the strength of this response; reserving is what
 * {@code POST /api/stock/reservations} is for, and it decides against the row rather than against a
 * number somebody read earlier.
 */
public record StockResponse(Long productId, int quantity) {

    public static StockResponse from(StockLevel stockLevel) {
        return new StockResponse(stockLevel.getProductId(), stockLevel.getQuantity());
    }

    /**
     * What a product with no stock row looks like.
     *
     * <p>Zero rather than a 404, because a missing row and a row holding zero mean the same thing to
     * every caller: you cannot buy this right now. With no foreign key to catalog-service, a missing
     * row is also the normal state of a product created a second ago whose stock has not been set
     * yet - and answering 404 would turn that ordinary intermediate state into an error for the
     * catalogue page.
     */
    public static StockResponse none(Long productId) {
        return new StockResponse(productId, 0);
    }
}
