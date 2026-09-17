package com.ecomdemo.notification;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A confirmation that was sent to a customer.
 *
 * <p>"Sent" is a log line in this phase - there is no mail server, and introducing one would be a
 * second technology. The row is what makes the send observable and testable: an assertion about a
 * log line is an assertion about a string, and an assertion about "exactly once" needs something
 * countable.
 *
 * <p>{@code customerId} and {@code orderId} are plain columns rather than {@code @ManyToOne}
 * associations, which is a deliberate departure from how {@code Order} is mapped. This entity is
 * built from an event, not from a query: it records what the event said, and it must not depend on
 * those rows still being there. It is also the seam along which a real notification service would
 * later be split out, and an association would have to be unpicked first.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The event that caused this. Kept so a duplicate is traceable, not merely prevented. */
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Notification() {
    }

    public Notification(UUID eventId, Long orderId, Long customerId, String message, Instant createdAt) {
        this.eventId = eventId;
        this.orderId = orderId;
        this.customerId = customerId;
        this.message = message;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public String getMessage() {
        return message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
