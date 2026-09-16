package com.ecomdemo.order.client;

import java.util.List;
import java.util.function.BiConsumer;

import com.ecomdemo.shared.ServiceUnavailableException;
import com.ecomdemo.support.TestFixtures;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration;

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
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * The circuit breaker's state machine, driven deliberately rather than waited for.
 *
 * <h2>How this test gets a real breaker</h2>
 *
 * Resilience4j's annotations are applied by an AspectJ aspect, so a plain
 * {@code new ResilientCatalogClient(...)} has no breaker at all - the same trap as
 * {@code @Transactional} on an un-proxied bean, and a test written that way would pass while
 * measuring nothing.
 *
 * <p>{@link ApplicationContextRunner} loading Resilience4j's own auto-configuration is what builds
 * the aspects, so the proxying, the thresholds and the fallback resolution are all real; only the
 * HTTP call underneath is a mock. Building the aspect by hand was the first attempt and is not worth
 * repeating - it reaches into internal types that are nobody's API, and the compile error only
 * appeared once the build was run without {@code -q}.
 *
 * <p>The thresholds here are deliberately smaller than production's - a window of 4 rather than 10 -
 * because the transitions are the subject, not the numbers. {@code ResilienceConfigurationTest}
 * checks that the real values bind.
 */
@ExtendWith(MockitoExtension.class)
// The helper stubs a working catalogue for every scenario; the tests about an open circuit never
// reach it, which is the point rather than an oversight.
@MockitoSettings(strictness = Strictness.LENIENT)
class ResilientCatalogClientTest {

    private static final CatalogProduct KEYBOARD =
            TestFixtures.product(1L, "Mechanical Keyboard", "129.99");

    @Mock
    private CatalogClient rawClient;

    /**
     * Runs a scenario inside a context holding the real aspects.
     *
     * <p>The body runs inside the runner's lambda because {@code ApplicationContextRunner} closes the
     * context when that lambda returns - assigning the beans to fields and using them afterwards
     * would operate on a closed context.
     */
    private void withBreaker(BiConsumer<CatalogClient, CircuitBreakerRegistry> scenario) {
        new ApplicationContextRunner()
                // Three auto-configurations, and each is load-bearing.
                //
                // AopAutoConfiguration: without auto-proxying the bean is registered unwrapped, every
                // annotation is inert, and the raw exception propagates as if no breaker existed. The
                // first run of this test failed exactly that way.
                //
                // RetryAutoConfiguration: needed even though this class is about the BREAKER, because
                // fallbackMethod lives on @Retry - the outermost decorator - so without the retry
                // aspect there is no fallback and the raw exception escapes. That coupling is the
                // cost of putting the fallback at the edge, and it is worth paying: see
                // CatalogRetryAndBreakerTest for the bug the alternative caused.
                .withConfiguration(AutoConfigurations.of(
                        AopAutoConfiguration.class,
                        CircuitBreakerAutoConfiguration.class,
                        RetryAutoConfiguration.class))
                .withBean(ResilientCatalogClient.class, () -> new ResilientCatalogClient(rawClient))
                .withPropertyValues(
                        "resilience4j.circuitbreaker.instances.catalog.slidingWindowType=COUNT_BASED",
                        "resilience4j.circuitbreaker.instances.catalog.slidingWindowSize=4",
                        "resilience4j.circuitbreaker.instances.catalog.minimumNumberOfCalls=4",
                        "resilience4j.circuitbreaker.instances.catalog.failureRateThreshold=50",
                        "resilience4j.circuitbreaker.instances.catalog.waitDurationInOpenState=200ms",
                        "resilience4j.circuitbreaker.instances.catalog.permittedNumberOfCallsInHalfOpenState=2",
                        // A 4xx is an answer, not a fault - the same rule the real YAML expresses.
                        "resilience4j.circuitbreaker.instances.catalog.ignoreExceptions[0]="
                                + "org.springframework.web.client.HttpClientErrorException",
                        "resilience4j.circuitbreaker.instances.catalog.registerHealthIndicator=false",
                        // Retry present but doing nothing: one attempt per call. This class counts
                        // breaker calls to reason about the sliding window, and real retries would
                        // make each user call three of them. Retry behaviour is
                        // CatalogRetryAndBreakerTest's subject, not this one's.
                        "resilience4j.retry.instances.catalog.maxAttempts=1")
                .run(context -> scenario.accept(
                        context.getBean(ResilientCatalogClient.class),
                        context.getBean(CircuitBreakerRegistry.class)));
    }

    private static CircuitBreaker breakerIn(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker(ResilientCatalogClient.CATALOG);
    }

    private void catalogueIsDown() {
        willThrow(new ResourceAccessException("connection refused")).given(rawClient).findAll();
    }

    private void catalogueIsUp() {
        given(rawClient.findAll()).willReturn(List.of(KEYBOARD));
    }

    /** Drives enough failures to fill the window and open the circuit. */
    private void failUntilOpen(CatalogClient client) {
        catalogueIsDown();
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(client::findAll).isInstanceOf(ServiceUnavailableException.class);
        }
    }

    @Nested
    class Closed {

        @Test
        void aWorkingCatalogue_always_leavesTheCircuitClosedAndTheAnswerUntouched() {
            withBreaker((client, registry) -> {
                // GIVEN
                catalogueIsUp();

                // WHEN
                List<CatalogProduct> products = client.findAll();

                // THEN the decoration is invisible when nothing is wrong - the property that makes
                // it safe to apply everywhere.
                assertThat(products).containsExactly(KEYBOARD);
                assertThat(breakerIn(registry).getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            });
        }

        @Test
        void aFailure_whileClosed_stillReachesTheDependency() {
            withBreaker((client, registry) -> {
                // GIVEN one failure, well short of the threshold
                catalogueIsDown();

                // WHEN
                assertThatThrownBy(client::findAll).isInstanceOf(ServiceUnavailableException.class);

                // THEN the call was attempted. A closed breaker blocks nothing; it only counts.
                verify(rawClient, atLeast(1)).findAll();
                assertThat(breakerIn(registry).getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            });
        }
    }

    @Nested
    class Open {

        @Test
        void enoughFailures_always_openTheCircuit() {
            withBreaker((client, registry) -> {
                // GIVEN / WHEN
                failUntilOpen(client);

                // THEN
                assertThat(breakerIn(registry).getState()).isEqualTo(CircuitBreaker.State.OPEN);
            });
        }

        @Test
        void anOpenCircuit_never_callsTheDependencyAtAll() {
            withBreaker((client, registry) -> {
                // GIVEN an open circuit
                failUntilOpen(client);
                reset(rawClient);

                // WHEN another request arrives
                assertThatThrownBy(client::findAll).isInstanceOf(ServiceUnavailableException.class);

                // THEN nothing was attempted.
                //
                // This is the whole value of a circuit breaker and the thing a retry alone cannot do:
                // while the dependency is down, order-service stops spending a 10-second read
                // timeout per request rediscovering it. Failing in microseconds keeps the threads
                // free for the endpoints that still work, and stops this service adding load to a
                // dependency that is already struggling.
                verify(rawClient, never()).findAll();
            });
        }

        @Test
        void anOpenCircuit_always_failsWithAMessageAShopperCouldRead() {
            withBreaker((client, registry) -> {
                // GIVEN
                failUntilOpen(client);

                // WHEN / THEN - a 503's message, not a stack trace and not a 409
                assertThatThrownBy(client::findAll)
                        .isInstanceOf(ServiceUnavailableException.class)
                        .hasMessageContaining("temporarily unavailable")
                        .hasMessageContaining("try again");
            });
        }
    }

    @Nested
    class HalfOpenAndRecovery {

        @Test
        void afterTheWaitDuration_theCircuit_letsProbesThroughAndClosesOnSuccess() {
            withBreaker((client, registry) -> {
                // GIVEN an open circuit
                failUntilOpen(client);
                assertThat(breakerIn(registry).getState()).isEqualTo(CircuitBreaker.State.OPEN);

                // WHEN the wait duration passes and the dependency has recovered.
                //
                // transitionToHalfOpenState() rather than a sleep: the state change is what matters
                // and a test that sleeps is a test that is slow and occasionally flaky on a loaded
                // machine. The automatic transition is configured in the real YAML and covered by
                // ResilienceConfigurationTest.
                breakerIn(registry).transitionToHalfOpenState();
                reset(rawClient);
                catalogueIsUp();

                // THEN the permitted probes succeed and the circuit closes. Half-open is what makes
                // a breaker self-healing: it risks a small, bounded number of real calls to find out
                // whether the dependency is back, rather than needing a human to reset it.
                assertThat(client.findAll()).containsExactly(KEYBOARD);
                assertThat(client.findAll()).containsExactly(KEYBOARD);
                assertThat(breakerIn(registry).getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            });
        }

        @Test
        void afterTheWaitDuration_aStillBrokenDependency_reopensTheCircuit() {
            withBreaker((client, registry) -> {
                // GIVEN an open circuit and a dependency that has NOT recovered
                failUntilOpen(client);
                breakerIn(registry).transitionToHalfOpenState();

                // WHEN the probes are attempted
                assertThatThrownBy(client::findAll).isInstanceOf(ServiceUnavailableException.class);
                assertThatThrownBy(client::findAll).isInstanceOf(ServiceUnavailableException.class);

                // THEN it goes straight back to open rather than closing optimistically, and the next
                // wait begins. A breaker that closed on hope would flap - alternating between
                // hammering a broken dependency and blocking a healthy one.
                assertThat(breakerIn(registry).getState()).isEqualTo(CircuitBreaker.State.OPEN);
            });
        }
    }

    @Nested
    class WhatIsNotAFailure {

        @Test
        void a404_never_countsTowardsOpeningTheCircuit() {
            withBreaker((client, registry) -> {
                // GIVEN a catalogue that is working and simply does not have this product
                given(rawClient.findById(9999L)).willThrow(HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND, "Not Found", null, null, null));

                // WHEN several shoppers ask for it
                for (int i = 0; i < 6; i++) {
                    assertThatThrownBy(() -> client.findById(9999L))
                            // AND the 404 reaches the caller unchanged - CartService is what turns it
                            // into this service's own NotFoundException
                            .isInstanceOf(HttpClientErrorException.class);
                }

                // THEN the circuit is untouched. Counting 4xx as failures is the classic way to cut
                // off a perfectly healthy dependency: enough people mistyping a product id would
                // take the catalogue offline for everybody.
                assertThat(breakerIn(registry).getState()).isEqualTo(CircuitBreaker.State.CLOSED);
                assertThat(breakerIn(registry).getMetrics().getNumberOfFailedCalls()).isZero();
            });
        }
    }
}
