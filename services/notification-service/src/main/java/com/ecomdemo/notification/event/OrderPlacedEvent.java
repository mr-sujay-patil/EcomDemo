package com.ecomdemo.notification.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The fact that an order was placed, as this service reads it off {@code orders.placed}.
 *
 * <h2>Why this is a second copy</h2>
 *
 * order-service declares a record of the same name and (currently) the same shape. Sharing one class
 * through shared-kernel would save forty lines and would undo the thing this phase is about: the two
 * services would then have to be compiled and deployed together, and an event contract that requires
 * a coordinated deploy is not a contract, it is a method call with extra steps.
 *
 * <p>The producer's copy and this one are two independent statements about the same message. They are
 * kept in step by the JSON field names, by a golden sample committed on both sides, and by the
 * evolution rule below - not by a compiler.
 *
 * <h2>Evolving it</h2>
 *
 * Add fields, never remove or rename them. A consumer written against the old shape keeps working:
 * Jackson ignores fields it does not know, and a field that is not sent arrives as null. This is the
 * same expand-then-contract discipline the database migrations follow, for the same reason - during a
 * rolling deploy, old and new code are both running, and here "old code" may be a service somebody
 * else deploys on their own schedule.
 *
 * <p>The asymmetry is worth internalising: the producer can add a field whenever it likes and this
 * service will not notice. If it ever <em>removes</em> one, this service finds out at runtime, on a
 * message, in production.
 *
 * @param eventId     identifies this <em>event</em>, not the order. The idempotency key: a consumer
 *                    that has already seen this id has already done the work, however many times the
 *                    record is delivered.
 * @param orderId     the order this happened to, and the message key.
 * @param customerId  who placed it - so this service need not call back into order-service to find
 *                    out. An event that has to be enriched by querying its producer has not decoupled
 *                    anything, and would make this service unavailable whenever that one was.
 * @param placedAt    when it happened, not when it was consumed.
 * @param totalAmount what was charged.
 * @param items       the purchased lines, with the name and price as they were at checkout.
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
}
