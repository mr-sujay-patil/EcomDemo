package com.ecomdemo.order.client;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.ecomdemo.shared.ServiceUnavailableException;

import io.github.resilience4j.springboot.bulkhead.autoconfigure.BulkheadAutoConfiguration;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

/**
 * The bulkhead: a cap on how many requests may be inside the reservation call at once.
 *
 * <h2>The failure being prevented</h2>
 *
 * A dependency that is <em>down</em> fails in milliseconds and costs nothing. A dependency that is
 * <em>slow</em> holds every caller's thread for the length of the read timeout - ten seconds here.
 * Tomcat's worker pool is bounded, so at enough concurrency every worker ends up parked inside this
 * one call and order-service stops serving everything, including endpoints that never touch
 * inventory. Nothing crashes; the service simply stops answering.
 *
 * <p>These tests hold calls open with a latch rather than sleeping, so the concurrency is
 * deterministic rather than a race against the scheduler.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResilientInventoryClientTest {

    private static final int MAX_CONCURRENT = 2;

    @Mock
    private InventoryClient rawClient;

    private static ReservationRequest reservation() {
        return new ReservationRequest("order-1", List.of(new ReservationRequest.Line(1L, 1)));
    }

    private void withBulkhead(Consumer<InventoryClient> scenario) {
        new ApplicationContextRunner()
                // Without AopAutoConfiguration the bean is registered unwrapped and @Bulkhead is
                // inert - see ResilientCatalogClientTest for how that failure presents.
                .withConfiguration(AutoConfigurations.of(
                        AopAutoConfiguration.class, BulkheadAutoConfiguration.class))
                .withBean(ResilientInventoryClient.class, () -> new ResilientInventoryClient(rawClient))
                .withPropertyValues(
                        "resilience4j.bulkhead.instances.inventory.maxConcurrentCalls=" + MAX_CONCURRENT,
                        // Reject rather than queue. Queueing turns thread exhaustion into memory
                        // exhaustion and adds latency to a request that is going to fail anyway.
                        "resilience4j.bulkhead.instances.inventory.maxWaitDuration=0")
                .run(context -> scenario.accept(context.getBean(ResilientInventoryClient.class)));
    }

    @Nested
    class UnderTheLimit {

        @Test
        void aReservation_whenThereIsCapacity_reachesInventoryUntouched() {
            withBulkhead(client -> {
                // GIVEN / WHEN
                client.reserve(reservation());

                // THEN the decoration is invisible when nothing is wrong
                verify(rawClient).reserve(reservation());
            });
        }

        @Test
        void a409_always_reachesTheCaller() {
            withBulkhead(client -> {
                // GIVEN inventory-service saying there is not enough stock
                willThrow(HttpClientErrorException.create(
                        HttpStatus.CONFLICT, "Conflict", null, null, null))
                        .given(rawClient).reserve(reservation());

                // WHEN / THEN it is re-thrown untouched. "You cannot buy this" must not become "we
                // are broken" - OrderPlacement is what turns it into the shopper-facing 409, and a
                // fallback that swallowed it would hide a normal business outcome behind a 503.
                assertThatThrownBy(() -> client.reserve(reservation()))
                        .isInstanceOf(HttpClientErrorException.class);
            });
        }
    }

    @Nested
    class OverTheLimit {

        @Test
        void aReservation_whenTheBulkheadIsFull_isRejectedImmediatelyWithA503() throws Exception {
            withBulkhead(client -> {
                CountDownLatch holdInside = new CountDownLatch(1);
                CountDownLatch bothInside = new CountDownLatch(MAX_CONCURRENT);

                // GIVEN every permit taken by calls that are parked inside inventory-service -
                // which is what a SLOW dependency looks like from in here
                willAnswer(invocation -> {
                    bothInside.countDown();
                    holdInside.await(10, TimeUnit.SECONDS);
                    return null;
                }).given(rawClient).reserve(reservation());

                ExecutorService pool = Executors.newFixedThreadPool(MAX_CONCURRENT);
                try {
                    for (int i = 0; i < MAX_CONCURRENT; i++) {
                        pool.submit(() -> client.reserve(reservation()));
                    }
                    assertThat(bothInside.await(10, TimeUnit.SECONDS))
                            .as("both permits should be held")
                            .isTrue();

                    // WHEN one more arrives
                    long startedAt = System.nanoTime();
                    assertThatThrownBy(() -> client.reserve(reservation()))
                            // THEN 503, not 409: no stock was taken and nothing about the cart is
                            // wrong. order-service is simply at capacity for this call.
                            .isInstanceOf(ServiceUnavailableException.class)
                            .hasMessageContaining("too many checkouts");
                    Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);

                    // AND it was refused immediately rather than queued behind the two in flight.
                    // Failing fast is what leaves capacity for the endpoints that still work; a
                    // queue would make the caller wait to be told no.
                    assertThat(waited).isLessThan(Duration.ofSeconds(1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                } finally {
                    holdInside.countDown();
                    pool.shutdownNow();
                }
            });
        }

        @Test
        void thePermits_always_comeBackWhenACallFinishes() throws Exception {
            withBulkhead(client -> {
                AtomicInteger calls = new AtomicInteger();
                willAnswer(invocation -> {
                    calls.incrementAndGet();
                    return null;
                }).given(rawClient).reserve(reservation());

                // GIVEN far more sequential calls than the limit
                for (int i = 0; i < 20; i++) {
                    client.reserve(reservation());
                }

                // THEN every one succeeded. A bulkhead limits CONCURRENCY, not throughput - it is
                // not a rate limiter, and confusing the two leads to caps that throttle a healthy
                // system.
                assertThat(calls.get()).isEqualTo(20);
            });
        }
    }

    @Nested
    class TheCompensatingCall {

        @Test
        void release_never_goesThroughTheBulkhead() {
            withBulkhead(client -> {
                CountDownLatch holdInside = new CountDownLatch(1);
                CountDownLatch bothInside = new CountDownLatch(MAX_CONCURRENT);
                willAnswer(invocation -> {
                    bothInside.countDown();
                    holdInside.await(10, TimeUnit.SECONDS);
                    return null;
                }).given(rawClient).reserve(reservation());

                ExecutorService pool = Executors.newFixedThreadPool(MAX_CONCURRENT);
                try {
                    // GIVEN a bulkhead with every permit taken
                    for (int i = 0; i < MAX_CONCURRENT; i++) {
                        pool.submit(() -> client.reserve(reservation()));
                    }
                    assertThat(bothInside.await(10, TimeUnit.SECONDS)).isTrue();

                    // WHEN a compensating release arrives
                    client.release(reservation());

                    // THEN it goes through. This is the call that puts back stock already taken for
                    // an order that failed to save; rejecting it to protect a thread pool would
                    // trade a transient capacity problem for a permanent data problem - stock
                    // reserved forever for an order nobody placed.
                    verify(rawClient).release(reservation());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                } finally {
                    holdInside.countDown();
                    pool.shutdownNow();
                }
            });
        }
    }
}
