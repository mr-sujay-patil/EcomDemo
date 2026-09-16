package com.ecomdemo.inventory;

import com.ecomdemo.inventory.dto.StockLevelRequest;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves {@code @Version} on {@link StockLevel} actually rejects a stale write.
 *
 * <p>This test was {@code OptimisticLockTest} in the product package until Phase 20, where it guarded
 * {@code products.stock_quantity}. The column moved and the test moved with it, unchanged in
 * substance: the mechanism, the failure it prevents and the reasoning are identical, which is itself
 * worth noticing. Optimistic locking protects <em>a row</em>, and it does not care which service
 * owns the table or how many processes are competing for it - the version check happens in the
 * database.
 *
 * <p>{@code StockReservationConcurrencyIT} asserts the invariant that matters to a customer - two
 * threads, one unit, one winner - but it cannot guarantee the two transactions overlap on any given
 * run. This test removes the scheduler from the picture entirely: it creates the stale write
 * deliberately, in a fixed order, so the failure is the version check and can be nothing else.
 *
 * <p>"Stale" here means exactly what it means in production: something read the row, something else
 * changed it, and then the first one tried to write what it had. With {@code open-in-view} disabled
 * and no surrounding transaction, a repository call returns a detached entity - precisely a snapshot
 * of the row as it was at read time.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
/*
 * Its own database. This test commits rows that are never rolled back - that is the whole point of
 * it - and the `test` profile's H2 otherwise lives for the entire JVM and is shared by every test
 * class in this module. A distinct URL gives this class a private schema that Flyway migrates on its
 * own.
 */
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:inventory-lock;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")
class StockOptimisticLockTest {

    private static final Long CONTENDED = 9001L;
    private static final Long UNCONTENDED = 9002L;

    @Autowired
    private StockService stockService;

    @Autowired
    private StockRepository stockRepository;

    @Test
    void save_whenTheRowChangedSinceItWasRead_throwsOptimisticLockingFailure() {
        // GIVEN a stock row, and a stale snapshot of it taken before anybody else touched it
        stockService.setQuantity(CONTENDED, new StockLevelRequest(10));
        StockLevel stale = stockRepository.findById(CONTENDED).orElseThrow();

        // WHEN somebody else updates the row first, taking version 0 to version 1
        stockService.setQuantity(CONTENDED, new StockLevelRequest(3));

        // AND the holder of the stale snapshot tries to write what it read
        stale.setQuantity(99);

        // THEN the update finds no row at version 0 and fails, rather than silently overwriting the
        // change it never saw. This is the lost update that READ COMMITTED does not prevent, and the
        // single reason stock is worth extracting into a service of its own.
        assertThatThrownBy(() -> stockRepository.saveAndFlush(stale))
                .isInstanceOf(OptimisticLockingFailureException.class);

        // AND the winner's value stands
        assertThat(stockService.findByProductId(CONTENDED).quantity()).isEqualTo(3);
    }

    @Test
    void save_whenNobodyElseTouchedTheRow_succeeds() {
        // GIVEN a stock row nobody is competing for
        stockService.setQuantity(UNCONTENDED, new StockLevelRequest(10));
        StockLevel read = stockRepository.findById(UNCONTENDED).orElseThrow();

        // WHEN it is written back
        read.setQuantity(7);
        stockRepository.saveAndFlush(read);

        // THEN the version check passes and the write lands. Optimistic locking costs nothing at all
        // when there is no contention - no lock is taken and nothing blocks, which is why it suits
        // stock: collisions happen on the last unit of a popular product, not on every read.
        assertThat(stockService.findByProductId(UNCONTENDED).quantity()).isEqualTo(7);
    }
}
