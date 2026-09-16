package com.ecomdemo.order.client;

import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * order-service's view of inventory-service: reserve stock, and put it back.
 *
 * <p>The whole cart goes in one request, which is the important part. One call per line would mean a
 * five-line order taking stock five times with no way to undo the first three when the fourth turned
 * out to be short - the atomicity Phase 0 built into checkout, lost by the act of moving stock behind
 * an HTTP call. One request keeps the decision inside one transaction in inventory-service's
 * database, where it can still be all-or-nothing.
 *
 * <p>A 409 from either method is a normal outcome, not an error to be logged and swallowed: it means
 * the shopper's cart no longer matches what is available, and the shopper needs to be told.
 */
@HttpExchange("/api/stock")
public interface InventoryClient {

    /** Takes the units out, all of them or none. 409 if there are not enough. */
    @PostExchange("/reservations")
    void reserve(ReservationRequest request);

    /**
     * Puts them back, for a checkout that reserved stock and then could not save its order.
     *
     * <p>Best effort, and the most important comment in this package. There is no transaction across
     * the two databases, so this call is a <em>compensation</em>: an attempt to undo, made after the
     * fact, over a network that may itself fail. If it fails, stock stays reserved for an order that
     * does not exist until somebody notices.
     *
     * <p>Making that reliable needs the reservation to be a durable record with a state machine and a
     * timeout, so an unconfirmed one can expire on its own rather than depending on the process that
     * created it still being alive. That is the saga pattern, and it is Phase 24.
     */
    @PostExchange("/releases")
    void release(ReservationRequest request);
}
