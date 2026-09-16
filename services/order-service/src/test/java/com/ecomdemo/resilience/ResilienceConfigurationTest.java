package com.ecomdemo.resilience;

import java.time.Duration;
import java.util.List;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.springboot.bulkhead.autoconfigure.BulkheadAutoConfiguration;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.HttpClientErrorException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the resilience configuration in {@code application.yml} actually binds to.
 *
 * <p>The same reasoning as {@code DatasourceProfileTest} and {@code KafkaConfigurationTest}: these
 * settings are strings in a YAML file until something reads them, and every mistake available here
 * is silent.
 *
 * <ul>
 *   <li>A misspelled instance name - {@code catalogue} for {@code catalog} - creates a second,
 *       default-configured breaker. Nothing fails; the thresholds are simply not the ones written
 *       down, and the breaker opens at 50 calls instead of 10.
 *   <li>Forgetting {@code ignoreExceptions} means 404s count as failures, so enough shoppers
 *       mistyping a product id would cut off a healthy catalogue.
 *   <li>Forgetting {@code enableRandomizedWait} removes the jitter, and the retry storm it prevents
 *       is invisible until the day a dependency falls over under load.
 * </ul>
 *
 * <p>No dependency is contacted. The registries hold their configuration and decorate nothing until
 * a call is made.
 */
class ResilienceConfigurationTest {

    /**
     * Binds the real {@code application.yml} under the {@code dev} profile - the configuration the
     * service actually runs with, rather than values restated in the test.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(
                    AopAutoConfiguration.class,
                    CircuitBreakerAutoConfiguration.class,
                    RetryAutoConfiguration.class,
                    BulkheadAutoConfiguration.class))
            .withPropertyValues("spring.profiles.active=dev");

    @Nested
    class TheCircuitBreaker {

        @Test
        void catalog_always_needsRealEvidenceBeforeItOpens() {
            runner.run(context -> {
                CircuitBreakerConfig config = context.getBean(CircuitBreakerRegistry.class)
                        .circuitBreaker("catalog").getCircuitBreakerConfig();

                // A count-based window: decide on the last N calls, not the last N seconds. On low
                // traffic a time-based window can hold two calls, and "50% of two" is noise.
                assertThat(config.getSlidingWindowType())
                        .isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
                assertThat(config.getSlidingWindowSize()).isEqualTo(10);
                assertThat(config.getFailureRateThreshold()).isEqualTo(50f);

                // And it will not judge before there is something to judge. Without a minimum, the
                // first failed call is a 100% failure rate and the circuit opens on one bad request.
                assertThat(config.getMinimumNumberOfCalls()).isEqualTo(5);
            });
        }

        @Test
        void catalog_always_probesRecoveryOnItsOwn() {
            runner.run(context -> {
                CircuitBreakerConfig config = context.getBean(CircuitBreakerRegistry.class)
                        .circuitBreaker("catalog").getCircuitBreakerConfig();

                assertThat(config.getWaitIntervalFunctionInOpenState().apply(1))
                        .isEqualTo(Duration.ofSeconds(10).toMillis());
                assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);

                // Half-open on a timer rather than waiting for a call. A service with no traffic
                // would otherwise never discover that its dependency came back.
                assertThat(config.isAutomaticTransitionFromOpenToHalfOpenEnabled()).isTrue();
            });
        }

        @Test
        void catalog_never_countsAClientErrorAsAFailure() {
            runner.run(context -> {
                CircuitBreakerConfig config = context.getBean(CircuitBreakerRegistry.class)
                        .circuitBreaker("catalog").getCircuitBreakerConfig();

                // A 404 is an ANSWER. Counting it as a failure is the classic way to cut off a
                // perfectly healthy dependency.
                //
                // Note the polarity, which differs between the two registries and is easy to invert:
                // the BREAKER exposes getIgnoreExceptionPredicate (true = ignore this), while the
                // RETRY below exposes getExceptionPredicate (true = retry this). Asserting the wrong
                // one passes while proving the opposite.
                assertThat(config.getIgnoreExceptionPredicate()
                        .test(HttpClientErrorException.create(
                                org.springframework.http.HttpStatus.NOT_FOUND,
                                "Not Found", null, null, null)))
                        .as("a 4xx must not count towards opening the circuit")
                        .isTrue();
            });
        }
    }

    @Nested
    class TheRetry {

        @Test
        void catalog_always_backsOffExponentiallyWithJitter() {
            runner.run(context -> {
                var config = context.getBean(RetryRegistry.class).retry("catalog").getRetryConfig();

                assertThat(config.getMaxAttempts()).isEqualTo(3);

                // Jitter is the line that prevents a retry storm, and it is the easiest to leave out
                // because nothing looks wrong without it. Every instance that failed together would
                // retry together, hitting a recovering dependency with a synchronised burst exactly
                // as it tries to come back.
                //
                // Randomised waits are asserted by observing that two intervals differ rather than
                // by reading a flag, because the flag is not exposed on the built config.
                long first = config.getIntervalBiFunction().apply(1, null);
                long second = config.getIntervalBiFunction().apply(1, null);
                long third = config.getIntervalBiFunction().apply(1, null);
                assertThat(List.of(first, second, third))
                        .as("randomised wait should not produce an identical interval every time")
                        .doesNotHaveDuplicates();

                // Exponential: attempt 2 waits materially longer than attempt 1.
                assertThat(config.getIntervalBiFunction().apply(3, null))
                        .isGreaterThan(config.getIntervalBiFunction().apply(1, null));
            });
        }

        @Test
        void catalog_never_retriesAClientError() {
            runner.run(context -> {
                var config = context.getBean(RetryRegistry.class).retry("catalog").getRetryConfig();

                // Retrying a 404 triples the load for a question that has already been answered.
                assertThat(config.getExceptionPredicate()
                        .test(HttpClientErrorException.create(
                                org.springframework.http.HttpStatus.NOT_FOUND,
                                "Not Found", null, null, null)))
                        .isFalse();
            });
        }
    }

    @Nested
    class TheBulkhead {

        @Test
        void inventory_always_failsFastRatherThanQueueing() {
            runner.run(context -> {
                var config = context.getBean(BulkheadRegistry.class)
                        .bulkhead("inventory").getBulkheadConfig();

                assertThat(config.getMaxConcurrentCalls()).isEqualTo(8);

                // Zero wait: reject immediately. Queueing converts thread exhaustion into memory
                // exhaustion and adds latency to a request that is going to fail anyway.
                assertThat(config.getMaxWaitDuration()).isEqualTo(Duration.ZERO);
            });
        }
    }
}
