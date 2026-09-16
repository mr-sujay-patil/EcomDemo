package com.ecomdemo.order;

import java.math.BigDecimal;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The business meters, against a real registry rather than a mock.
 *
 * <p>{@link SimpleMeterRegistry} is a stub in the sense Phase 2 meant it: a real implementation with
 * known behaviour and nothing to arrange. Mocking {@code MeterRegistry} would let "the counter went
 * up" pass while the counter was never registered, which is the only way this code can be wrong.
 */
class OrderMetricsTest {

    private MeterRegistry registry;
    private OrderMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new OrderMetrics(registry);
    }

    @Nested
    class RecordPlacedOrder {

        @Test
        void recordPlacedOrder_always_countsTheOrderAndRecordsItsValue() {
            // GIVEN nothing recorded yet

            // WHEN
            metrics.recordPlacedOrder(new BigDecimal("24.00"));

            // THEN
            assertThat(registry.get(OrderMetrics.ORDERS_PLACED).counter().count()).isEqualTo(1.0);
            assertThat(registry.get(OrderMetrics.ORDER_VALUE).summary().count()).isEqualTo(1L);
            assertThat(registry.get(OrderMetrics.ORDER_VALUE).summary().totalAmount()).isEqualTo(24.0);
        }

        @Test
        void recordPlacedOrder_forSeveralOrders_keepsCountAndTotalSeparately() {
            // GIVEN two orders of different sizes
            // WHEN
            metrics.recordPlacedOrder(new BigDecimal("10.00"));
            metrics.recordPlacedOrder(new BigDecimal("90.00"));

            // THEN the summary carries both the how-many and the how-much, which is the whole reason
            // it is a DistributionSummary and not a second counter.
            assertThat(registry.get(OrderMetrics.ORDER_VALUE).summary().count()).isEqualTo(2L);
            assertThat(registry.get(OrderMetrics.ORDER_VALUE).summary().totalAmount()).isEqualTo(100.0);
            assertThat(registry.get(OrderMetrics.ORDER_VALUE).summary().max()).isEqualTo(90.0);
        }
    }

    @Nested
    class RecordCheckout {

        @Test
        void recordCheckout_always_recordsOneSampleUnderTheGivenOutcome() {
            // GIVEN a started attempt
            var sample = metrics.startCheckout();

            // WHEN
            metrics.recordCheckout(sample, OrderMetrics.OUTCOME_SUCCESS);

            // THEN
            assertThat(registry.get(OrderMetrics.CHECKOUT)
                    .tag("outcome", OrderMetrics.OUTCOME_SUCCESS)
                    .timer().count()).isEqualTo(1L);
        }

        @Test
        void recordCheckout_withDifferentOutcomes_keepsOneTimeSeriesPerOutcome() {
            // GIVEN one attempt that succeeded and two that lost a race
            // WHEN
            metrics.recordCheckout(metrics.startCheckout(), OrderMetrics.OUTCOME_SUCCESS);
            metrics.recordCheckout(metrics.startCheckout(), OrderMetrics.OUTCOME_CONFLICT);
            metrics.recordCheckout(metrics.startCheckout(), OrderMetrics.OUTCOME_CONFLICT);

            // THEN each tag value is its own series - which is what makes the contention rate
            // readable, and also why an unbounded value must never go in a tag.
            assertThat(registry.get(OrderMetrics.CHECKOUT)
                    .tag("outcome", OrderMetrics.OUTCOME_SUCCESS).timer().count()).isEqualTo(1L);
            assertThat(registry.get(OrderMetrics.CHECKOUT)
                    .tag("outcome", OrderMetrics.OUTCOME_CONFLICT).timer().count()).isEqualTo(2L);
            assertThat(registry.get(OrderMetrics.CHECKOUT).timers()).hasSize(2);
        }
    }

    /**
     * The contract between this code and the files in {@code docker/}.
     *
     * <p>Grafana panels and the Prometheus alert refer to metrics by their <em>scraped</em> name,
     * which Micrometer derives from the meter name rather than copying it - dots become underscores,
     * a counter gains {@code _total}, a timer gains its unit. Rename a constant in
     * {@link OrderMetrics} and nothing here fails to compile, nothing logs a warning, and the
     * dashboard quietly draws a flat line forever. This test is what turns that into a build failure.
     */
    @Nested
    class TheScrapedNames {

        @Test
        void meters_whenScraped_carryTheNamesTheDashboardAndAlertRuleUse() {
            // GIVEN the Prometheus registry, whose naming convention is the one that matters
            PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
            OrderMetrics prometheusMetrics = new OrderMetrics(prometheus);

            // WHEN one order is placed and one attempt timed
            prometheusMetrics.recordPlacedOrder(new BigDecimal("24.00"));
            prometheusMetrics.recordCheckout(prometheusMetrics.startCheckout(), OrderMetrics.OUTCOME_SUCCESS);
            String scrape = prometheus.scrape();

            // THEN
            assertThat(scrape)
                    .as("the counter, with _total and without a base unit glued into the middle")
                    .contains("orders_placed_total")
                    .doesNotContain("orders_placed_orders_total");
            assertThat(scrape)
                    .as("the summary, as count/sum plus histogram buckets")
                    .contains("order_value_count", "order_value_sum", "order_value_bucket");
            assertThat(scrape)
                    .as("the timer, in seconds and tagged by outcome")
                    .contains("order_checkout_seconds_count", "outcome=\"success\"");
        }
    }
}
