package com.ecomdemo.product;

import java.math.BigDecimal;

import com.ecomdemo.product.dto.ProductRequest;
import com.ecomdemo.product.dto.ProductResponse;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves {@code @Version} on {@link Product} actually rejects a stale write.
 *
 * <p>{@code ConcurrentOrderTest} asserts the invariant that matters to a customer - two threads, one
 * unit, one order - but it cannot guarantee the two transactions overlap on any given run, and when
 * they do not the loser is turned away by an empty cart rather than by the lock. This test removes
 * the scheduler from the picture entirely: it creates the stale write deliberately, in a fixed
 * order, so the failure is the version check and can be nothing else.
 *
 * <p>"Stale" here means exactly what it means in production: something read the row, something else
 * changed it, and then the first one tried to write what it had. With {@code open-in-view} disabled
 * and no surrounding transaction, a repository call returns a detached entity - which is precisely a
 * snapshot of the row as it was at read time.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
/**
 * Its own database. This test commits rows that are never rolled back - that is the whole point of
 * it - and the {@code test} profile's H2 otherwise lives for the entire JVM and is shared by every
 * test class. Committed orders would then leak into {@code OrderRepositoryTest}, whose assertions
 * are about an empty table, and the suite would pass or fail depending on the order JUnit happened
 * to pick. A distinct URL gives this class a private schema that Flyway migrates on its own.
 */
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:ecomdemo-lock;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")
class OptimisticLockTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Test
    void save_whenTheRowChangedSinceItWasRead_throwsOptimisticLockingFailure() {
        // GIVEN a product, and a stale snapshot of it taken before anybody else touched it
        ProductResponse created = productService.create(new ProductRequest(
                "Contended Product", "For the lock test", new BigDecimal("25.00"), 10, null));
        Product stale = productRepository.findById(created.id()).orElseThrow();

        // WHEN somebody else updates the row first, taking version 0 to version 1
        productService.update(created.id(), new ProductRequest(
                "Contended Product", "Updated by someone else", new BigDecimal("25.00"), 3, null));

        // AND the holder of the stale snapshot tries to write what it read
        stale.setStockQuantity(99);

        // THEN the update finds no row at version 0 and fails, rather than silently overwriting the
        // change it never saw. This is the lost update that READ COMMITTED does not prevent.
        assertThatThrownBy(() -> productRepository.saveAndFlush(stale))
                .isInstanceOf(OptimisticLockingFailureException.class);

        // AND the winner's value stands
        assertThat(productService.findById(created.id()).stockQuantity()).isEqualTo(3);
    }

    @Test
    void save_whenNobodyElseTouchedTheRow_succeeds() {
        // GIVEN a product nobody is competing for
        ProductResponse created = productService.create(new ProductRequest(
                "Uncontended Product", "No competition", new BigDecimal("25.00"), 10, null));
        Product read = productRepository.findById(created.id()).orElseThrow();

        // WHEN it is written back
        read.setStockQuantity(7);
        productRepository.saveAndFlush(read);

        // THEN the version check passes and the write lands. Optimistic locking costs nothing at all
        // when there is no contention - no lock is taken and nothing blocks, which is exactly why it
        // suits a catalogue that is read constantly and written rarely.
        assertThat(productService.findById(created.id()).stockQuantity()).isEqualTo(7);
    }
}
