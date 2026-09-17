package com.ecomdemo.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.ecomdemo.inventory.dto.ReservationRequest;
import com.ecomdemo.inventory.dto.StockLevelRequest;
import com.ecomdemo.support.AbstractInventoryServiceIT;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two threads, one unit of stock, exactly one winner.
 *
 * <p>This is the test the whole service exists for, and it is {@code ConcurrentOrderIT} from Phase 6
 * rewritten for where the contention now lives. Without {@code @Version} on {@link StockLevel} both
 * threads read {@code quantity = 1}, both write {@code 0}, and one unit is sold twice. That is a lost
 * update, and it is the anomaly READ COMMITTED - PostgreSQL's default - specifically does <em>not</em>
 * prevent: neither transaction read dirty data and neither re-read anything. They simply both wrote.
 *
 * <p>Run against real PostgreSQL rather than H2 for the obvious reason: PostgreSQL is what production
 * runs, and a concurrency guarantee verified on a different engine is a guarantee about that engine.
 *
 * <h2>What the split changed, and what it did not</h2>
 *
 * Nothing about the mechanism. The version check happens inside the database and does not care
 * whether the two contenders are threads in one JVM or HTTP requests from two order-service
 * instances on different hosts. That is worth dwelling on, because it is the one concurrency control
 * in this system that survived being distributed unchanged - precisely because it was never in the
 * application to begin with.
 *
 * <p>What did change is the number of contenders. In the monolith, checkouts competed within one
 * process; now every order-service instance is an independent source of reservations against the same
 * row, and the retry is what absorbs the collisions that follow.
 *
 * <h2>Why it is not flaky</h2>
 *
 * The assertion is the invariant - one reservation succeeds, stock reaches zero, never negative - not
 * <em>how</em> the loser lost. A loser may be refused by the version check (which the retry may then
 * turn into a plain "not enough stock") or simply arrive second and find nothing left. Both endings
 * are correct, and demanding a particular one would make this a test of thread scheduling that fails
 * on a loaded CI box.
 */
class StockReservationConcurrencyIT extends AbstractInventoryServiceIT {

    private static final int ROUNDS = 5;

    @Autowired
    private StockService stockService;

    @Test
    void reserve_whenTwoThreadsTakeTheLastUnit_exactlyOneSucceeds() throws Exception {
        for (int round = 1; round <= ROUNDS; round++) {
            // GIVEN a product with exactly one unit left
            Long productId = freshProductId() + round;
            stockService.setQuantity(productId, new StockLevelRequest(1));

            // WHEN two threads try to reserve it at the same moment
            List<Boolean> outcomes = raceTwoReservations(productId);

            // THEN exactly one of them got it
            assertThat(outcomes).as("round %d", round)
                    .containsExactlyInAnyOrder(true, false);

            // AND the database agrees: the unit was sold exactly once and never oversold
            assertThat(stockService.findByProductId(productId).quantity())
                    .as("round %d never oversold", round)
                    .isZero();
        }
    }

    @Test
    void reserve_whenTenThreadsTakeFromFiveUnits_stopsAtExactlyFive() throws Exception {
        // GIVEN five units and ten shoppers
        Long productId = freshProductId();
        stockService.setQuantity(productId, new StockLevelRequest(5));

        // WHEN they all go at once
        List<Boolean> outcomes = race(10, productId);

        // THEN five succeeded and five were refused - and, more to the point, the quantity never went
        // below zero. The CHECK constraint in V1 would have failed the transaction if it had; the
        // version column is what stops it being attempted.
        assertThat(outcomes).filteredOn(succeeded -> succeeded).hasSize(5);
        assertThat(stockService.findByProductId(productId).quantity()).isZero();
    }

    private List<Boolean> raceTwoReservations(Long productId) throws Exception {
        return race(2, productId);
    }

    /**
     * Fires {@code threads} single-unit reservations at the same product as close to simultaneously
     * as the scheduler allows, and reports which ones succeeded.
     *
     * <p>The {@link CyclicBarrier} is what makes the race a race: every thread is created, has its
     * Spring context warm and its connection ready, and then waits. Without it the first thread would
     * usually finish before the last had started, and the test would pass while proving nothing.
     */
    private List<Boolean> race(int threads, Long productId) throws Exception {
        CyclicBarrier startLine = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                attempts.add(() -> {
                    startLine.await(10, TimeUnit.SECONDS);
                    try {
                        stockService.reserve(new ReservationRequest(
                                "race", List.of(new ReservationRequest.Line(productId, 1))));
                        return true;
                    } catch (RuntimeException refused) {
                        // Either "not enough stock" or an optimistic lock failure that outlived the
                        // retries. Both mean the same thing to a shopper, which is why the test does
                        // not distinguish them.
                        return false;
                    }
                });
            }

            List<Boolean> outcomes = new ArrayList<>();
            for (Future<Boolean> future : pool.invokeAll(attempts, 30, TimeUnit.SECONDS)) {
                outcomes.add(future.get());
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
