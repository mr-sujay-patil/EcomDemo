package com.ecomdemo.order;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.CartService;
import com.ecomdemo.cart.dto.CartItemResponse;
import com.ecomdemo.product.ProductService;
import com.ecomdemo.product.dto.ProductRequest;
import com.ecomdemo.product.dto.ProductResponse;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two threads, one unit of stock, and exactly one order.
 *
 * <p>This is the test the phase exists for. Without {@code @Version} on {@code Product} both threads
 * read {@code stockQuantity = 1}, both write {@code 0}, and both orders are created - the database
 * ends up having sold two of something it had one of. That is a lost update, and it is the anomaly
 * READ COMMITTED (PostgreSQL's default, and H2's here) specifically does <em>not</em> prevent:
 * neither transaction read dirty data, and neither re-read anything. They simply both wrote.
 *
 * <p><strong>Why this is not flaky.</strong> The assertion is on the invariant - one order, zero
 * stock, never oversold - not on <em>how</em> the loser lost. Depending on how the two transactions
 * interleave, the loser either hits the version conflict and then finds an empty cart on its retry,
 * or never overlaps at all and simply finds the cart empty first time. Both are correct outcomes and
 * both satisfy every assertion here. A test demanding that the version conflict fire on a particular
 * run would be a test of the scheduler, and it would fail on a slow CI box.
 *
 * <p>The race is run several times because interleavings are a matter of luck: repetition makes it
 * very likely that the genuine version conflict path is exercised at least once, while each
 * individual round still asserts only what must always be true.
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
        "spring.datasource.url=jdbc:h2:mem:ecomdemo-concurrent;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")
class ConcurrentOrderTest {

    private static final int ROUNDS = 5;

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductService productService;

    @Autowired
    private CartService cartService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderAuditRepository orderAuditRepository;

    @Test
    void placeOrder_whenTwoThreadsBuyTheLastUnit_exactlyOneSucceeds() throws Exception {
        int conflictsObserved = 0;

        for (int round = 1; round <= ROUNDS; round++) {
            // GIVEN a product with exactly one unit left, sitting in the shared cart
            emptyTheCart();
            ProductResponse lastUnit = productService.create(new ProductRequest(
                    "Last Unit " + System.nanoTime(), "One in stock", new BigDecimal("10.00"), 1, null));
            cartService.addItem(new AddCartItemRequest(lastUnit.id(), 1));

            long ordersBefore = orderRepository.count();

            // WHEN two threads try to buy it at the same moment
            List<Attempt> attempts = raceTwoCheckouts();

            // THEN exactly one of them placed an order
            assertThat(attempts).filteredOn(Attempt::succeeded).hasSize(1);
            assertThat(attempts).filteredOn(attempt -> !attempt.succeeded()).hasSize(1);

            // AND the database agrees: one new order, and the unit was sold exactly once
            assertThat(orderRepository.count())
                    .as("round %d created exactly one order", round)
                    .isEqualTo(ordersBefore + 1);
            assertThat(productService.findById(lastUnit.id()).stockQuantity())
                    .as("round %d never oversold", round)
                    .isZero();

            // AND the cart is empty - the winner consumed it
            assertThat(cartService.getCart().items()).isEmpty();

            if (attempts.stream().anyMatch(Attempt::wasVersionConflict)) {
                conflictsObserved++;
            }
        }

        // The audit trail recorded every attempt, winners and losers alike, because those writes use
        // REQUIRES_NEW and so outlived the rolled-back transactions.
        assertThat(orderAuditRepository.findByOutcome(OrderAudit.Outcome.PLACED))
                .hasSizeGreaterThanOrEqualTo(ROUNDS);

        System.out.println("[ConcurrentOrderTest] genuine version conflicts in " + ROUNDS
                + " rounds: " + conflictsObserved);
    }

    /** Releases both threads from the same barrier, so they enter checkout together. */
    private List<Attempt> raceTwoCheckouts() throws Exception {
        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Attempt> checkout = () -> {
                startLine.await(10, TimeUnit.SECONDS);
                try {
                    orderService.placeOrder();
                    return new Attempt(true, null);
                } catch (RuntimeException ex) {
                    return new Attempt(false, ex);
                }
            };

            List<Future<Attempt>> futures = pool.invokeAll(List.of(checkout, checkout));
            List<Attempt> attempts = new ArrayList<>();
            for (Future<Attempt> future : futures) {
                attempts.add(future.get(20, TimeUnit.SECONDS));
            }
            return attempts;
        } finally {
            pool.shutdownNow();
        }
    }

    private void emptyTheCart() {
        for (CartItemResponse item : cartService.getCart().items()) {
            cartService.removeItem(item.productId());
        }
    }

    /**
     * One thread's outcome. The failure is kept rather than asserted on directly: which of the two
     * legitimate failures the loser hits depends on the interleaving, and pinning that down would
     * make this a test of thread scheduling.
     */
    private record Attempt(boolean succeeded, RuntimeException failure) {

        boolean wasVersionConflict() {
            return failure instanceof org.springframework.dao.OptimisticLockingFailureException;
        }
    }
}
