package com.ecomdemo.messaging;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A record that one event has already been handled.
 *
 * <p>This is how an at-least-once delivery is turned into an effectively-once outcome. Kafka will
 * hand the same record to a consumer more than once - after a crash between the work and the offset
 * commit, or after a rebalance - and the broker cannot do better than that without the consumer's
 * help. This table is the consumer's half of the bargain.
 *
 * <p>Note what it does <em>not</em> have: a generated id. The event id is the primary key, so the
 * uniqueness is the database's and not the application's. A {@code findBy...isEmpty()} check in Java
 * has a window between reading and writing that two threads can both pass through; a primary key has
 * no such window.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    /**
     * Which kind of event it was. Not used to make a decision - it is here because an operator
     * looking at this table needs to know what an id refers to, and because the id space is shared
     * by every event type this application will ever consume.
     */
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
    }

    public ProcessedEvent(UUID eventId, String eventType, Instant processedAt) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.processedAt = processedAt;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
