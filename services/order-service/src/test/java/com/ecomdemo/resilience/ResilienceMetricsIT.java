package com.ecomdemo.resilience;

import com.ecomdemo.support.AbstractOrderServiceIT;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The resilience meters reach {@code /actuator/prometheus}.
 *
 * <p>Worth a test of its own because the failure is silent and the symptom appears somewhere else
 * entirely: the circuit breaker works perfectly, the Grafana panel is permanently empty, and the
 * natural conclusion is that the dashboard query is wrong. It is the same shape as Phase 15's
 * missing Prometheus registry - the auto-configuration present, the thing it configures absent.
 *
 * <p>Asserting the exact series names also pins the contract the dashboard is written against. A
 * Resilience4j upgrade that renamed {@code resilience4j_circuitbreaker_state} would otherwise be
 * discovered by a human noticing a flat line.
 */
class ResilienceMetricsIT extends AbstractOrderServiceIT {

    private String scrape() {
        return anonymous.get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult().getResponseBody();
    }

    @Test
    void theCircuitBreakerState_always_appearsInTheScrape() {
        // GIVEN / WHEN
        String body = scrape();

        // THEN the series the Grafana panel plots, and the instance label it filters on.
        //
        // resilience4j_circuitbreaker_state is one series per state with a 0/1 value, not a single
        // enum - which is why the dashboard panel plots `max by (state)` rather than the raw value.
        assertThat(body).contains("resilience4j_circuitbreaker_state");
        assertThat(body).contains("name=\"catalog\"");
    }

    @Test
    void theCallCounters_always_appearSoAFailureRateCanBeComputed() {
        // GIVEN / WHEN
        String body = scrape();

        // THEN - a state graph says what the breaker decided; these say why. Without them "the
        // circuit opened" is an event with no explanation.
        assertThat(body).contains("resilience4j_circuitbreaker_calls");
        assertThat(body).contains("resilience4j_circuitbreaker_failure_rate");
    }

    @Test
    void theBulkhead_always_reportsItsRemainingCapacity() {
        // GIVEN / WHEN
        String body = scrape();

        // THEN available_concurrent_calls is the number worth alerting on: it falling to zero is the
        // early warning that inventory-service is slowing down, and it moves before any request has
        // actually been rejected.
        assertThat(body).contains("resilience4j_bulkhead_available_concurrent_calls");
        assertThat(body).contains("name=\"inventory\"");
    }

    @Test
    void everyResilienceSeries_always_carriesTheServiceName() {
        // GIVEN / WHEN
        String body = scrape();

        // THEN the common tag from shared-kernel reaches these meters too.
        //
        // It is a MeterRegistryCustomizer applied to the registry rather than to individual meters,
        // so meters registered later by a third-party binder pick it up automatically. With five
        // services scraping into one Prometheus, a circuit breaker series that did not say which
        // service owned it would be unusable.
        String circuitBreakerLine = body.lines()
                .filter(line -> line.startsWith("resilience4j_circuitbreaker_state"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no circuit breaker series in the scrape"));

        assertThat(circuitBreakerLine).contains("application=\"order-service\"");
    }
}
