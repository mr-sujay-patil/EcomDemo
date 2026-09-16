package com.ecomdemo.inventory;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.ecomdemo.inventory.dto.ReservationRequest;
import com.ecomdemo.shared.ConflictException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One attempt at reserving a cart's worth of stock, inside one transaction.
 *
 * <p>Split out of {@link StockService} so that the retry can live <em>outside</em> the transaction -
 * the same arrangement, and for the same reason, as {@code OrderPlacement} and {@code OrderService}
 * in Phase 6. Once a transaction has been marked rollback-only there is nothing left to retry inside
 * it, and a retry loop in the same bean would not help anyway: Spring applies {@code @Transactional}
 * with a proxy, and a {@code this.reserveOnce()} call from a neighbouring method never reaches it.
 * Two beans make the boundary real rather than a comment asking people to remember it.
 *
 * <p>The method must stay public, because Spring's proxies ignore non-public methods and the
 * transaction would silently never start.
 *
 * <h2>Atomicity</h2>
 *
 * Every line is checked before anything is written, so a five-line reservation whose fourth line is
 * short changes nothing at all rather than half-completing. The transaction turns that from a
 * convention into a guarantee.
 *
 * <p>Worth being precise about what it guarantees, because this is exactly where a distributed system
 * gets misread: this transaction covers <em>this database</em>. It makes the five stock decrements
 * atomic with each other. It says nothing about the order row order-service is about to write, which
 * is in another database and can still fail after this has committed. See
 * {@code OrderPlacement} there for what is done about that, and what is not.
 */
@Component
@Transactional(readOnly = true)
class StockReservation {

    private static final Logger log = LoggerFactory.getLogger(StockReservation.class);

    private final StockRepository stockRepository;

    StockReservation(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    /** Takes the units out, or changes nothing and throws. */
    @Transactional
    public void reserveOnce(ReservationRequest request) {
        Map<Long, StockLevel> levels = load(request);

        // 1. Check every line first. Availability may have moved since the cart was filled, and a
        //    partially applied reservation is not something this service could take back on its own.
        for (ReservationRequest.Line line : request.lines()) {
            StockLevel level = levels.get(line.productId());
            int available = level == null ? 0 : level.getQuantity();
            if (level == null || !level.hasAtLeast(line.quantity())) {
                throw new ConflictException("Only " + available + " unit(s) of product "
                        + line.productId() + " in stock, requested " + line.quantity());
            }
        }

        // 2. Only then write.
        for (ReservationRequest.Line line : request.lines()) {
            levels.get(line.productId()).reduce(line.quantity());
        }

        // Force the writes out now rather than at commit.
        //
        // The optimistic lock fires on the UPDATE, and without this flush that happens after this
        // method returns, while the proxy is committing - too late for @Retryable to see anything
        // but a failed commit. Flushing brings the version check inside the method, where the retry
        // can act on it. The same trick, for the same reason, as OrderPlacement's flush in Phase 6.
        stockRepository.flush();

        log.info("Reserved {} line(s) for {}", request.lines().size(),
                request.orderReference() == null ? "an unnamed order" : request.orderReference());
    }

    /** Puts the units back. */
    @Transactional
    public void releaseOnce(ReservationRequest request) {
        Map<Long, StockLevel> levels = load(request);

        for (ReservationRequest.Line line : request.lines()) {
            StockLevel level = levels.get(line.productId());
            if (level == null) {
                // Nothing to put it back into. Logged rather than thrown: a release is a
                // compensation for something that already went wrong, and failing it would replace
                // one problem with two.
                log.warn("Cannot release {} unit(s) of product {}: no stock row",
                        line.quantity(), line.productId());
                continue;
            }
            level.increase(line.quantity());
        }

        stockRepository.flush();
        log.info("Released {} line(s) for {}", request.lines().size(),
                request.orderReference() == null ? "an unnamed order" : request.orderReference());
    }

    /** Every row this request touches, in one query rather than one per line. */
    private Map<Long, StockLevel> load(ReservationRequest request) {
        List<Long> productIds = request.lines().stream()
                .map(ReservationRequest.Line::productId)
                .distinct()
                .toList();

        return stockRepository.findAllByProductIdIn(productIds).stream()
                .collect(Collectors.toMap(StockLevel::getProductId, Function.identity()));
    }
}
