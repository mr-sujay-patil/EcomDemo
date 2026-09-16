package com.ecomdemo.order.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ecomdemo.order.dto.OrderResponse;

/**
 * The fact that an order was placed, as it travels over Kafka.
 *
 * <h2>Why this is not {@code OrderResponse}</h2>
 *
 * It looks almost the same, and reusing the DTO would save forty lines. The reason not to is that
 * these two records answer to different people. {@code OrderResponse} is shaped by what an HTTP
 * client needs to render and can be changed whenever that client is changed with it. This one is
 * shaped by what a subscriber needs, and its subscribers are not deployed at the same time as this
 * application - a field renamed here breaks a consumer that is still running the old code, weeks
 * later, with no compiler anywhere to notice. Keeping them separate means a change to the API does
 * not silently become a change to the event contract.
 *
 * <p>The same reasoning is why the entity is not published directly: an event is a public schema,
 * and publishing the schema of your own tables invites everyone else to depend on it.
 *
 * <h2>Evolving it</h2>
 *
 * Add fields, never remove or rename them, and consumers written against the old shape keep working
 * ({@code JsonDeserializer} ignores fields it does not know, and a missing one arrives as null).
 * This is the same expand-then-contract discipline the database migrations follow, for the same
 * reason: during a rolling deploy, old and new code are both running.
 *
 * @param eventId    identifies this <em>event</em>, not the order. The idempotency key: a consumer
 *                   that has already seen this id has already done the work, however many times the
 *                   record is delivered. Generated once, at publication, so a re-delivery of the
 *                   same record carries the same id - which is exactly what makes it usable.
 * @param orderId    the order this happened to, and the message key.
 * @param customerId who placed it, so a consumer need not call back into this application to find
 *                   out. An event that has to be enriched by querying its producer has not really
 *                   decoupled anything.
 * @param placedAt   when it happened, from the injected {@code Clock} - not when it was consumed.
 * @param totalAmount what was charged, snapshotted like the order itself.
 * @param items      the purchased lines, with the name and price as they were at checkout.
 */
public record OrderPlacedEvent(
        UUID eventId,
        Long orderId,
        Long customerId,
        Instant placedAt,
        BigDecimal totalAmount,
        List<Line> items) {

    /** One purchased line. Deliberately flat - an event is a message, not an object graph. */
    public record Line(Long productId, String productName, BigDecimal unitPrice, int quantity) {
    }

    /**
     * Builds the event from the order that was just committed.
     *
     * <p>The {@code eventId} is generated here rather than taken from the caller, so there is one
     * place where it comes into existence and no way to publish an event without one.
     */
    public static OrderPlacedEvent from(OrderResponse order, Long customerId) {
        return new OrderPlacedEvent(
                UUID.randomUUID(),
                order.id(),
                customerId,
                order.placedAt(),
                order.totalAmount(),
                order.items().stream()
                        .map(item -> new Line(item.productId(), item.productName(),
                                item.unitPrice(), item.quantity()))
                        .toList());
    }
}
