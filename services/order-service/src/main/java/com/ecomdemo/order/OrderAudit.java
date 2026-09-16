package com.ecomdemo.order;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row per attempt to place an order, successful or not.
 *
 * <p>Written in its own transaction ({@link OrderAuditService}), so it outlives a rollback of the
 * checkout it describes. An audit trail that disappeared whenever the thing it recorded failed would
 * be recording only the uninteresting half.
 *
 * <p>There is deliberately no {@code @ManyToOne} to {@link Order}: a failed attempt has no order to
 * point at, and a cascade must never be able to delete the record of what happened. {@code orderId}
 * is a plain column for exactly that reason.
 */
@Entity
@Table(name = "order_audit")
public class OrderAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /**
     * Stored as a string, not an ordinal. {@code EnumType.ORDINAL} would write 0, 1, 2 - and
     * reordering the enum would silently rewrite the meaning of every historical row.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Outcome outcome;

    @Column(nullable = false, length = 500)
    private String detail;

    @Column(name = "order_id")
    private Long orderId;

    protected OrderAudit() {
    }

    public OrderAudit(Instant occurredAt, Outcome outcome, String detail, Long orderId) {
        this.occurredAt = occurredAt;
        this.outcome = outcome;
        this.detail = detail;
        this.orderId = orderId;
    }

    public Long getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public String getDetail() {
        return detail;
    }

    public Long getOrderId() {
        return orderId;
    }

    /** Why an attempt ended the way it did. */
    public enum Outcome {

        /** The order was created, stock reduced and the cart emptied. */
        PLACED,

        /** At least one line asked for more than the stock on hand. */
        INSUFFICIENT_STOCK,

        /** There was nothing to turn into an order. */
        EMPTY_CART,

        /** Another transaction changed a product first; the optimistic lock rejected this write. */
        CONCURRENT_MODIFICATION
    }
}
