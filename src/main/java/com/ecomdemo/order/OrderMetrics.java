package com.ecomdemo.order;

import java.math.BigDecimal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.stereotype.Component;

/**
 * The three business meters for checkout, in one place.
 *
 * <h2>Why a class rather than annotations</h2>
 *
 * Micrometer ships {@code @Timed} and {@code @Counted}, but they need their aspects registered
 * explicitly and they can only measure "this method ran". Two of the three measurements here are
 * about the <em>result</em> - the basket total, and whether checkout succeeded or lost a race - which
 * an annotation cannot see. Registering the meters by hand also means the names and tags are
 * declared once, in code that can be read, instead of being scattered across annotation attributes.
 *
 * <h2>Why the meters are fields</h2>
 *
 * {@link Counter#builder} and friends are <em>lookup-or-create</em>: calling them per request would
 * work, but it hashes the name and tags on every call. Resolving them once in the constructor is the
 * documented pattern and makes the cost of instrumentation a field read.
 *
 * <p>The exception is {@link #recordCheckout}, which resolves its timer per call. That one carries an
 * {@code outcome} tag, and a tag whose value varies is a different meter per value - so it cannot be
 * a single field. The set of values is closed and tiny ({@code success}, {@code conflict},
 * {@code error}), which is what makes it safe: <strong>every distinct tag value is a separate time
 * series</strong>, and putting something unbounded there - a customer id, an order id - is the
 * fastest way to bring down a Prometheus.
 */
@Component
public class OrderMetrics {

    /** Orders that were actually placed. Not attempts - see {@link #recordCheckout}. */
    static final String ORDERS_PLACED = "orders.placed";

    /** The monetary value of those orders. */
    static final String ORDER_VALUE = "order.value";

    /** How long one checkout attempt took, and how it ended. */
    static final String CHECKOUT = "order.checkout";

    static final String OUTCOME_SUCCESS = "success";
    static final String OUTCOME_CONFLICT = "conflict";
    static final String OUTCOME_ERROR = "error";

    private final MeterRegistry registry;
    private final Counter ordersPlaced;
    private final DistributionSummary orderValue;

    OrderMetrics(MeterRegistry registry) {
        this.registry = registry;

        /*
         * No .baseUnit("orders").
         *
         * Micrometer's Prometheus naming convention appends the base unit to the metric name, so
         * that call would publish orders_placed_orders_total rather than orders_placed_total - the
         * unit is meant for things like bytes and seconds, where it disambiguates. A count of
         * orders is already named for what it counts.
         */
        this.ordersPlaced = Counter.builder(ORDERS_PLACED)
                .description("Orders successfully placed")
                .register(registry);

        /*
         * A DistributionSummary, not a Counter.
         *
         * A counter of revenue would answer "how much have we taken?" and nothing else. A summary
         * records every observation, so it also answers "what does a typical basket look like?" -
         * it keeps a count, a sum (revenue, still available) and, with the histogram below, the
         * percentiles. Mean basket value is derivable from the first two; the shape is not.
         *
         * The histogram is published here rather than configured in YAML because this meter is
         * defined in code and the buckets are domain knowledge: baskets in this shop are tens to
         * hundreds, so a range of 1 to 5000 covers the distribution without wasting buckets on
         * values that cannot occur.
         */
        this.orderValue = DistributionSummary.builder(ORDER_VALUE)
                .description("Total value of each placed order")
                .publishPercentileHistogram()
                .minimumExpectedValue(1.0)
                .maximumExpectedValue(5000.0)
                .register(registry);
    }

    /**
     * Starts the clock for one checkout attempt.
     *
     * <p>A {@link Timer.Sample} rather than {@code timer.record(Runnable)} because the outcome tag is
     * not known until the work has finished - and a failed checkout is exactly as worth measuring as
     * a successful one. Timing only the happy path is how a service comes to look fast while its
     * users wait.
     */
    Timer.Sample startCheckout() {
        return Timer.start(registry);
    }

    /**
     * Records a completed attempt under its outcome.
     *
     * <p>Called once per <em>attempt</em>, so a checkout that loses an optimistic lock and is retried
     * contributes one {@code conflict} sample and then one {@code success} sample. That is the honest
     * reading: {@code order_checkout_seconds_count{outcome="conflict"}} is the contention rate, and
     * the end-to-end latency the customer actually experienced is {@code http_server_requests} for
     * {@code POST /api/orders}, which spans every retry.
     */
    void recordCheckout(Timer.Sample sample, String outcome) {
        sample.stop(Timer.builder(CHECKOUT)
                .description("Duration of one checkout attempt")
                .tag("outcome", outcome)
                .register(registry));
    }

    /**
     * Records a placed order.
     *
     * <p>Deliberately called from {@link OrderService}, outside the transaction, and never from
     * {@code OrderPlacement} inside it. <strong>A meter is not transactional.</strong> An increment
     * inside a transaction that later rolls back is not undone - there is no rollback hook on a
     * counter - so the count would drift permanently upward with nothing to reconcile it against.
     * Recording after the transactional call has returned means the number counts orders that really
     * exist in the database.
     */
    void recordPlacedOrder(BigDecimal totalAmount) {
        ordersPlaced.increment();
        orderValue.record(totalAmount.doubleValue());
    }
}
