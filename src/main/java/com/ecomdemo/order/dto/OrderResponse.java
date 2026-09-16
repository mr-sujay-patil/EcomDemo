package com.ecomdemo.order.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.ecomdemo.order.Order;

public record OrderResponse(
        Long id,
        Instant placedAt,
        List<OrderItemResponse> items,
        BigDecimal totalAmount) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getPlacedAt(),
                order.getItems().stream().map(OrderItemResponse::from).toList(),
                order.getTotalAmount());
    }
}
