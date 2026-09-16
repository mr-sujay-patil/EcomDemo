package com.ecomdemo.order.dto;

import java.math.BigDecimal;

import com.ecomdemo.order.OrderItem;

/** One purchased line as the client sees it - the snapshotted name and price, not the live ones. */
public record OrderItemResponse(
        Long productId,
        String productName,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal lineTotal) {

    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(
                item.getProductId(),
                item.getProductName(),
                item.getUnitPrice(),
                item.getQuantity(),
                item.lineTotal());
    }
}
