package com.ecomdemo.inventory;

import java.util.List;

import com.ecomdemo.inventory.dto.ReservationRequest;
import com.ecomdemo.inventory.dto.StockLevelRequest;
import com.ecomdemo.inventory.dto.StockResponse;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The stock API the rest of the system calls.
 *
 * <p>Reads run in a read-only transaction (the class-level annotation). Reserving does not run in a
 * transaction at all - it retries one, which is a different job and has to happen outside.
 *
 * <p>Nothing here is cached, and that is a decision rather than an omission. Phase 13's rule was
 * "cache what is read to be displayed, never what is read to make a decision", and every number this
 * service returns feeds a decision. It is also the only service in the system with no Redis
 * dependency at all, which makes the rule impossible to break here by accident.
 */
@Service
@Transactional(readOnly = true)
public class StockService {

    /**
     * Two retries, so three attempts in total. Small on purpose: a version conflict means someone
     * else got there first, and if that keeps happening the honest answer to the caller is 409, not
     * an ever-longer wait. Retrying forever under contention is how a slow endpoint becomes an
     * outage.
     */
    static final long MAX_RETRIES = 2;

    private final StockRepository stockRepository;
    private final StockReservation stockReservation;

    public StockService(StockRepository stockRepository, StockReservation stockReservation) {
        this.stockRepository = stockRepository;
        this.stockReservation = stockReservation;
    }

    /**
     * How many of one product are available, or zero if this service has never heard of it.
     *
     * <p>Zero rather than 404 - see {@link StockResponse#none}. With no foreign key to the catalogue,
     * "no row" is indistinguishable from "a product created a moment ago", and neither is an error.
     */
    public StockResponse findByProductId(Long productId) {
        return stockRepository.findById(productId)
                .map(StockResponse::from)
                .orElseGet(() -> StockResponse.none(productId));
    }

    /**
     * Stock for several products at once.
     *
     * <p>Exists so a caller rendering a page of products makes one call instead of twenty. It is the
     * shape a gateway doing aggregation in Phase 21 will want, and the shape that keeps a cheap
     * in-process loop from becoming an expensive network one after the split.
     */
    public List<StockResponse> findByProductIds(List<Long> productIds) {
        List<StockResponse> known = stockRepository.findAllByProductIdIn(productIds).stream()
                .map(StockResponse::from)
                .toList();

        // Products with no row still get an answer, so the caller can zip the two lists together
        // without having to work out which ids went missing.
        List<Long> found = known.stream().map(StockResponse::productId).toList();
        return java.util.stream.Stream.concat(
                        known.stream(),
                        productIds.stream().distinct().filter(id -> !found.contains(id)).map(StockResponse::none))
                .toList();
    }

    /**
     * Sets a product's stock to an absolute figure. Creates the row if this is the first time.
     *
     * <p>An upsert rather than a create-then-update pair, because the caller - an administrator, or a
     * script restocking overnight - has no way to know whether the row exists and should not have to
     * care. It is also idempotent, which matters for anything reachable over a network: a client
     * that retries a request whose response was lost gets the same result rather than a 409.
     */
    @Transactional
    public StockResponse setQuantity(Long productId, StockLevelRequest request) {
        StockLevel level = stockRepository.findById(productId)
                .orElseGet(() -> new StockLevel(productId, 0));
        level.setQuantity(request.quantity());
        return StockResponse.from(stockRepository.save(level));
    }

    /**
     * Reserves a cart's worth of stock, retrying if another transaction got there first.
     *
     * <p>Three things about this method are deliberate and easy to get wrong, and they are the same
     * three as {@code OrderService.placeOrder} in Phase 6:
     *
     * <ul>
     *   <li>It delegates to {@link StockReservation}, a <em>different bean</em>. Calling a
     *       {@code @Transactional} method on {@code this} would bypass the proxy and run with no
     *       transaction at all, silently.
     *   <li>{@link Propagation#NEVER} opts out of the class-level {@code readOnly = true}. Without
     *       it the retry would run inside a read-only transaction that {@code StockReservation} would
     *       then join - and a rolled-back transaction cannot be retried from inside itself.
     *   <li>Retries are bounded. When they run out the {@code OptimisticLockingFailureException}
     *       propagates and {@code GlobalExceptionHandler} answers 409.
     * </ul>
     *
     * <p>What moved in Phase 20 is <em>where</em> the contention is. It used to be on the products
     * row, contended by checkouts inside one application. It is on the stock_levels row now,
     * contended by HTTP requests from however many order-service instances are running - which is a
     * larger number of contenders and exactly the same mechanism, because the version check happens
     * in the database and does not care who asked.
     */
    @Retryable(includes = OptimisticLockingFailureException.class, maxRetries = MAX_RETRIES,
            delay = 25, jitter = 25)
    @Transactional(propagation = Propagation.NEVER)
    public void reserve(ReservationRequest request) {
        stockReservation.reserveOnce(request);
    }

    /**
     * Puts a reservation back.
     *
     * <p>This is the compensating action for a checkout that reserved stock and then failed to save
     * its order - see {@code OrderPlacement} in order-service. It exists because there is no
     * transaction spanning the two databases and there cannot be one.
     *
     * <p><strong>It is best-effort by construction.</strong> Nothing here records that a reservation
     * ever happened, so this service cannot tell a genuine release from a replayed one, cannot notice
     * that a release never arrived, and cannot expire a reservation whose owner crashed. Making those
     * possible means a reservation table, a state machine and a timeout - a saga, which is Phase 24.
     * Retried on a version conflict for the same reason the reservation is: losing a race is not a
     * reason to give up putting stock back.
     */
    @Retryable(includes = OptimisticLockingFailureException.class, maxRetries = MAX_RETRIES,
            delay = 25, jitter = 25)
    @Transactional(propagation = Propagation.NEVER)
    public void release(ReservationRequest request) {
        stockReservation.releaseOnce(request);
    }
}
