package com.ecomdemo.notification.dto;

import java.time.Instant;

import com.ecomdemo.notification.Notification;

/** One confirmation as the client sees it. The event id is not exposed - it is plumbing. */
public record NotificationResponse(Long id, Long orderId, String message, Instant createdAt) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getOrderId(),
                notification.getMessage(),
                notification.getCreatedAt());
    }
}
