package com.ecomdemo.order.client;

import com.ecomdemo.shared.ServiceUnavailableException;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * inventory-service, behind a bulkhead.
 *
 * <h2>What a bulkhead is for, and why this call gets one</h2>
 *
 * The name is from shipbuilding: a hull divided into sealed compartments so that one breach floods
 * one compartment instead of the ship. Here the compartment is a cap on how many requests may be
 * inside this call at once.
 *
 * <p>The failure it prevents is the one that makes a slow dependency worse than a dead one. A
 * dependency that is <em>down</em> fails in milliseconds - connection refused - and costs nothing. A
 * dependency that is <em>slow</em> holds every caller's thread for the length of the read timeout,
 * ten seconds here. Tomcat has a bounded worker pool; at enough concurrency every worker ends up
 * parked inside this one call, and order-service stops serving <strong>everything</strong>, including
 * {@code GET /api/orders}, which never touches inventory at all. One slow neighbour takes out
 * endpoints that had no dependency on it. That is a cascading failure, and it happens without
 * anything crashing.
 *
 * <p>Eight concurrent calls, and the ninth is rejected immediately rather than queued
 * ({@code maxWaitDuration: 0}). Queueing would convert thread exhaustion into memory exhaustion and
 * add latency to a request that is going to fail anyway; failing fast is what leaves capacity for
 * the endpoints that still work.
 *
 * <h2>Why here and not on the catalogue</h2>
 *
 * Reserving stock is the call worth protecting: it is on the checkout path, it mutates another
 * service's state, and it is the one order-service cannot simply degrade around. The catalogue has a
 * circuit breaker instead, which is the better tool when the dependency is failing outright rather
 * than merely slow - the two are complements, and a system usually wants both on its most important
 * call.
 *
 * <h2>No circuit breaker on reserve(), deliberately</h2>
 *
 * A breaker short-circuits calls it believes will fail. For a read that is free; for a write it is
 * not, because "rejected without trying" and "tried and failed" are indistinguishable to the caller,
 * and the caller here has already taken stock in inventory-service's database when it comes to call
 * {@link #release}. Short-circuiting a compensating release would strand exactly the stock the
 * release exists to return - which is why {@code release} is outside the bulkhead entirely.
 */
@Component
@Primary
public class ResilientInventoryClient implements InventoryClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientInventoryClient.class);

    /** Names the instance configured under {@code resilience4j.bulkhead.instances.inventory}. */
    static final String INVENTORY = "inventory";

    private final InventoryClient delegate;

    public ResilientInventoryClient(@Qualifier("rawInventoryClient") InventoryClient delegate) {
        this.delegate = delegate;
    }

    @Override
    @Bulkhead(name = INVENTORY, fallbackMethod = "reserveRejected")
    public void reserve(ReservationRequest request) {
        delegate.reserve(request);
    }

    /**
     * Not bulkheaded, and not fallen back.
     *
     * <p>This is the compensating call for a checkout that already took stock. Rejecting it to
     * protect a thread pool would leave stock reserved for an order that does not exist - trading a
     * transient capacity problem for a permanent data problem. It is already best-effort (see
     * {@code OrderPlacement}); narrowing its chances further would be the wrong economy.
     */
    @Override
    public void release(ReservationRequest request) {
        delegate.release(request);
    }

    /**
     * The bulkhead is full: this request never reached inventory-service.
     *
     * <p>503, not 409. No stock was taken and nothing about the cart is wrong - order-service is
     * simply at capacity for this call. A well-behaved client retries after the {@code Retry-After}.
     */
    @SuppressWarnings("unused")
    private void reserveRejected(ReservationRequest request, BulkheadFullException ex) {
        log.warn("Inventory bulkhead full; reservation for {} not attempted", request.orderReference());
        throw new ServiceUnavailableException(
                "We are handling too many checkouts right now. Please try again in a moment.", ex);
    }

    /**
     * Anything else the delegate threw.
     *
     * <p>Re-thrown untouched. A 409 from inventory-service means "not enough stock" and must reach
     * {@code OrderPlacement}, which turns it into the shopper-facing conflict. Swallowing it here
     * would turn "you cannot buy this" into "we are broken".
     */
    @SuppressWarnings("unused")
    private void reserveRejected(ReservationRequest request, Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw new IllegalStateException(cause);
    }
}
