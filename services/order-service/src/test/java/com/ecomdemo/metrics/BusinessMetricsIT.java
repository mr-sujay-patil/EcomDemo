package com.ecomdemo.metrics;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartItemResponse;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.support.AbstractOrderServiceIT;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The business meters, end to end: place a real order over HTTP and read the numbers back out of the
 * scrape endpoint the way Prometheus would.
 *
 * <p>{@link com.ecomdemo.order.OrderMetricsTest} already proves the meters record what they are told
 * to. What it cannot prove is that {@code OrderService} actually calls them on the path a real
 * request takes - through the security filter chain, the retry proxy and a committed transaction.
 * That is the gap this closes, and it is the reason the assertions are all <em>deltas</em>.
 *
 * <p><strong>Deltas, never absolutes.</strong> Every integration test in the run shares one
 * application and one registry, and meters are process-wide and cumulative - so by the time this
 * class runs, {@code PlaceOrderIT} has already placed orders. Asserting {@code orders_placed_total
 * == 1} would pass alone and fail in a full build, which is the worst kind of test.
 */
class BusinessMetricsIT extends AbstractOrderServiceIT {

    @BeforeEach
    void emptyTheCart() {
        CartResponse cart = client.get().uri("/api/cart").exchange()
                .expectStatus().isOk()
                .expectBody(CartResponse.class).returnResult().getResponseBody();
        for (CartItemResponse item : cart.items()) {
            client.delete().uri("/api/cart/items/" + item.productId()).exchange().expectStatus().isOk();
        }
    }

    private String scrape() {
        return anonymous.get().uri("/actuator/prometheus").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
    }

    /**
     * Reads one sample out of the exposition format.
     *
     * <p>Parsing the scrape rather than reading the {@code MeterRegistry} bean directly is
     * deliberate: the registry would prove the counter moved, while this proves the counter moved
     * <em>and</em> is rendered under the name the dashboard and the alert rule query. Those are two
     * different failures and only one of them is visible from inside the JVM.
     */
    private double sample(String scrape, String metricAndLabels) {
        Pattern pattern = Pattern.compile(
                "^" + Pattern.quote(metricAndLabels) + "\\s+([0-9.eE+-]+)$", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(scrape);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : 0.0;
    }

    private static final String ORDERS_PLACED =
            "orders_placed_total{application=\"order-service\"}";
    private static final String ORDER_VALUE_COUNT =
            "order_value_count{application=\"order-service\"}";
    private static final String ORDER_VALUE_SUM =
            "order_value_sum{application=\"order-service\"}";
    private static final String CHECKOUT_SUCCESS =
            "order_checkout_seconds_count{application=\"order-service\",outcome=\"success\"}";
    private static final String CHECKOUT_CONFLICT =
            "order_checkout_seconds_count{application=\"order-service\",outcome=\"conflict\"}";

    @Test
    void placingAnOrder_always_movesTheCounterTheSummaryAndTheTimerTogether() {
        // GIVEN a product in the cart and the meters as they stand right now.
        //
        // The product comes from the stubbed catalogue rather than from POST /api/products - that
        // endpoint is in catalog-service now, and this suite has no way to reach it. KEYBOARD is
        // priced at 129.99; two of them is 259.98, which is what the revenue assertion below expects.
        client.post().uri("/api/cart/items")
                .body(new AddCartItemRequest(KEYBOARD.id(), 2))
                .exchange().expectStatus().isOk();

        String before = scrape();
        double placedBefore = sample(before, ORDERS_PLACED);
        double valueCountBefore = sample(before, ORDER_VALUE_COUNT);
        double valueSumBefore = sample(before, ORDER_VALUE_SUM);
        double successBefore = sample(before, CHECKOUT_SUCCESS);

        // WHEN the order is placed over real HTTP
        client.post().uri("/api/orders").exchange().expectStatus().isCreated();

        // THEN all three moved, by the amounts the order was worth
        String after = scrape();
        assertThat(sample(after, ORDERS_PLACED))
                .as("orders.placed counts exactly one more order")
                .isEqualTo(placedBefore + 1);
        assertThat(sample(after, ORDER_VALUE_COUNT))
                .as("order.value observed exactly one more basket")
                .isEqualTo(valueCountBefore + 1);
        assertThat(sample(after, ORDER_VALUE_SUM))
                .as("2 keyboards at 129.99 - the summary keeps the revenue as well as the shape")
                .isEqualTo(valueSumBefore + 259.98);
        assertThat(sample(after, CHECKOUT_SUCCESS))
                .as("one successful checkout attempt was timed")
                .isEqualTo(successBefore + 1);
    }

    @Test
    void aRefusedCheckout_always_isTimedAsAConflictAndCountsNoOrder() {
        // GIVEN an empty cart, which checkout refuses with 409
        String before = scrape();
        double placedBefore = sample(before, ORDERS_PLACED);
        double conflictBefore = sample(before, CHECKOUT_CONFLICT);

        // WHEN
        client.post().uri("/api/orders").exchange().expectStatus().isEqualTo(409);

        // THEN the attempt is measured - a failed checkout is exactly as worth measuring as a
        // successful one, and timing only the happy path is how a service comes to look fast while
        // its users wait...
        String after = scrape();
        assertThat(sample(after, CHECKOUT_CONFLICT))
                .as("the refused attempt was timed under outcome=conflict")
                .isEqualTo(conflictBefore + 1);

        // ...but nothing was sold, so the business counter must not move. This is the assertion that
        // would catch orders.placed being incremented before the work rather than after it.
        assertThat(sample(after, ORDERS_PLACED))
                .as("no order exists, so orders.placed is unchanged")
                .isEqualTo(placedBefore);
    }

    @Test
    void httpMetrics_afterTraffic_carryTheUriTemplateRatherThanTheRawPath() {
        // GIVEN a request to a templated path
        client.get().uri("/api/orders").exchange().expectStatus().isOk();

        // WHEN the scrape is read
        String scrape = scrape();

        // THEN the uri tag is the template. One series per concrete id would be unbounded
        // cardinality - the single fastest way to bring down a Prometheus - and Spring's
        // instrumentation avoids it by tagging with the mapping, not the request.
        assertThat(scrape).contains("uri=\"/api/orders\"");
        assertThat(scrape)
                .as("the outcome tag is what the alert rule filters on")
                .contains("outcome=\"SUCCESS\"");
    }
}
