package com.ecomdemo.inventory.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * "Take these units out of stock, all of them or none."
 *
 * <p>The whole cart arrives in one request, and that is the important part of the design rather than
 * an optimisation. One line per request would mean a five-line order taking stock five times, with
 * no way to undo the first three when the fourth turns out to be short - the atomicity Phase 0 built
 * into checkout would be lost precisely by moving stock behind an HTTP call. One request keeps the
 * decision inside one database transaction, where it can still be all-or-nothing.
 *
 * @param orderReference an id the caller uses to describe this reservation in its own logs. It is
 *                       recorded nowhere and reserves nothing by itself: this service has no
 *                       reservation table and cannot tell a retry from a second attempt. A durable
 *                       reservation that could be looked up, expired and idempotently re-submitted
 *                       is the saga machinery of Phase 24, and pretending to have it here would be
 *                       worse than not having it.
 */
public record ReservationRequest(

        String orderReference,

        @NotEmpty(message = "must contain at least one line")
        @Valid
        List<Line> lines) {

    public record Line(

            @NotNull(message = "is required")
            Long productId,

            @NotNull(message = "is required")
            @Positive(message = "must be greater than zero")
            Integer quantity) {
    }
}
