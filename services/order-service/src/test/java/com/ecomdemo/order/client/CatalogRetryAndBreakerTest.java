package com.ecomdemo.order.client;

import java.util.List;
import java.util.function.BiConsumer;

import com.ecomdemo.shared.ServiceUnavailableException;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Retry and circuit breaker together, against the real {@code application.yml}.
 *
 * <h2>Why this exists separately from {@code ResilientCatalogClientTest}</h2>
 *
 * That class loads the circuit breaker aspect alone, which is right for testing the state machine
 * and is precisely why it could not catch the bug this class pins down. The two decorators only
 * misbehave in combination.
 *
 * <p>The bug, found by running the stack rather than by any test: {@code fallbackMethod} was on
 * {@code @CircuitBreaker}. A fallback runs inside the aspect that declares it, so an open circuit's
 * {@code CallNotPermittedException} was translated into {@code ServiceUnavailableException} before
 * the outer {@code @Retry} saw it - and the retry's {@code ignoreExceptions} entry for
 * {@code CallNotPermittedException} could not match a type it never received. The retry therefore
 * retried an open circuit three times, spending ~700ms and two backoff sleeps to be told the same no,
 * when the entire point of an open circuit is to fail in microseconds.
 *
 * <p>Both tests below fail if the fallback moves back onto the breaker.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CatalogRetryAndBreakerTest {

    @Mock
    private CatalogClient rawClient;

    /**
     * Loads BOTH aspects and the real configuration.
     *
     * <p>{@link ConfigDataApplicationContextInitializer} binds {@code application.yml} under the
     * {@code dev} profile, so the thresholds, the backoff and the {@code ignoreExceptions} lists are
     * the ones that ship rather than values restated here.
     */
    private void withRetryAndBreaker(BiConsumer<CatalogClient, CircuitBreakerRegistry> scenario) {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(
                        AopAutoConfiguration.class,
                        CircuitBreakerAutoConfiguration.class,
                        RetryAutoConfiguration.class))
                .withBean(ResilientCatalogClient.class, () -> new ResilientCatalogClient(rawClient))
                .withPropertyValues("spring.profiles.active=dev")
                .run(context -> scenario.accept(
                        context.getBean(ResilientCatalogClient.class),
                        context.getBean(CircuitBreakerRegistry.class)));
    }

    @Test
    void aTransportFailure_always_isRetriedTheConfiguredNumberOfTimes() {
        withRetryAndBreaker((client, registry) -> {
            // GIVEN a catalogue that refuses connections
            willThrow(new ResourceAccessException("connection refused")).given(rawClient).findAll();

            // WHEN one call is made
            assertThatThrownBy(client::findAll).isInstanceOf(ServiceUnavailableException.class);

            // THEN it was attempted three times - maxAttempts, not three retries after the first.
            // A transient failure is exactly what a retry is for, and this is the behaviour that
            // must survive the fix below.
            verify(rawClient, times(3)).findAll();
        });
    }

    @Test
    void anOpenCircuit_never_isRetried() {
        withRetryAndBreaker((client, registry) -> {
            // GIVEN a circuit driven open by real failures
            willThrow(new ResourceAccessException("connection refused")).given(rawClient).findAll();
            CircuitBreaker breaker = registry.circuitBreaker(ResilientCatalogClient.CATALOG);
            while (breaker.getState() != CircuitBreaker.State.OPEN) {
                assertThatThrownBy(client::findAll).isInstanceOf(ServiceUnavailableException.class);
            }

            long rejectedBefore = breaker.getMetrics().getNumberOfNotPermittedCalls();
            clearInvocations(rawClient);

            // WHEN one more request arrives
            assertThatThrownBy(client::findAll).isInstanceOf(ServiceUnavailableException.class);

            // THEN the breaker rejected it EXACTLY ONCE, not three times.
            //
            // This is the assertion the live run produced and no test had: with the fallback on the
            // breaker, this delta was 3. Retrying a rejection cannot help - the breaker's answer will
            // not change within a 200ms backoff - and it holds a request thread for the duration.
            assertThat(breaker.getMetrics().getNumberOfNotPermittedCalls() - rejectedBefore)
                    .as("an open circuit must reject once per request, not once per retry attempt")
                    .isEqualTo(1);

            // AND nothing reached the network at all.
            verify(rawClient, times(0)).findAll();
        });
    }

    @Test
    void theFallback_always_producesTheSameShopperFacingErrorFromEitherCause() {
        withRetryAndBreaker((client, registry) -> {
            // GIVEN a failing catalogue
            willThrow(new ResourceAccessException("connection refused")).given(rawClient).findAll();

            // WHEN the retries are exhausted while the circuit is still closed
            assertThatThrownBy(client::findAll)
                    // THEN one sentence a shopper could read, whatever the underlying cause.
                    // Translation happens once, at the outermost layer, after every decorator has
                    // had its say - which is exactly why the fallback belongs on @Retry.
                    .isInstanceOf(ServiceUnavailableException.class)
                    .hasMessageContaining("temporarily unavailable");

            // AND the same is true once the circuit is open, from a completely different cause
            CircuitBreaker breaker = registry.circuitBreaker(ResilientCatalogClient.CATALOG);
            while (breaker.getState() != CircuitBreaker.State.OPEN) {
                assertThatThrownBy(client::findAll).isInstanceOf(ServiceUnavailableException.class);
            }
            assertThatThrownBy(client::findAll)
                    .isInstanceOf(ServiceUnavailableException.class)
                    .hasMessageContaining("temporarily unavailable");
        });
    }

    @Test
    void aWorkingCatalogue_always_passesThroughUndecorated() {
        withRetryAndBreaker((client, registry) -> {
            // GIVEN
            var product = com.ecomdemo.support.TestFixtures.product(1L, "Keyboard", "129.99");
            org.mockito.BDDMockito.given(rawClient.findAll()).willReturn(List.of(product));

            // WHEN / THEN one call, one answer. Two layers of protection cost nothing when nothing
            // is wrong, which is what makes them safe to leave on.
            assertThat(client.findAll()).containsExactly(product);
            verify(rawClient, times(1)).findAll();
        });
    }
}
