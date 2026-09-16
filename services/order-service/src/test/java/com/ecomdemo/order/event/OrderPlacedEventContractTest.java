package com.ecomdemo.order.event;

import java.util.List;
import java.util.UUID;

import com.ecomdemo.order.dto.OrderItemResponse;
import com.ecomdemo.order.dto.OrderResponse;
import com.ecomdemo.support.TestFixtures;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The producer's half of the {@code orders.placed} contract.
 *
 * <h2>Why this test exists</h2>
 *
 * notification-service reads this event with its own copy of the record, in another module, that
 * nothing here compiles against. Nothing but a convention keeps the two in step - and a convention
 * with no test is a convention that will be broken by a rename during a refactor, discovered in
 * production, by a customer who never got their confirmation.
 *
 * <p>So this asserts the <em>JSON field names</em> rather than the Java shape. Renaming
 * {@code totalAmount} to {@code total} would leave every other test in this module green, because
 * every other test goes through the record. This one fails, and its failure message names the field.
 *
 * <p>The matching half is notification-service's {@code OrderPlacedNotificationIT}, which publishes a
 * hand-written sample with exactly these names and asserts a notification comes out. Together they
 * are consumer-driven contract testing without the framework: cheap, and honest about being two
 * assertions that a human has to keep in agreement.
 *
 * <h2>The rule these tests encode</h2>
 *
 * Add fields freely; never rename or remove one. A consumer ignores what it does not recognise and
 * receives null for what it expected and did not get, so additions are safe in either deployment
 * order. A rename is two deploys - add the new name, wait for every reader to move, remove the old -
 * which is the expand-then-contract discipline of Phase 5's migrations applied to a message.
 */
class OrderPlacedEventContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static OrderPlacedEvent sampleEvent() {
        OrderResponse order = new OrderResponse(
                77L,
                java.time.Instant.parse("2026-09-17T10:15:30Z"),
                List.of(new OrderItemResponse(
                        1L, "Mechanical Keyboard", TestFixtures.money("129.99"), 2,
                        TestFixtures.money("259.98"))),
                TestFixtures.money("259.98"));
        return OrderPlacedEvent.from(order, 42L);
    }

    @Test
    void theEvent_always_serialisesWithTheFieldNamesConsumersReadBy() {
        // GIVEN an event built the way checkout builds one
        // WHEN it is written exactly as the producer writes it
        JsonNode json = MAPPER.valueToTree(sampleEvent());

        // THEN every name notification-service reads by is present and spelled this way.
        //
        // If you are here because this test failed after a rename: the rename is the breaking change,
        // not this assertion. Add the new name alongside the old one, deploy the consumers, then
        // remove the old one in a later release.
        assertThat(json.propertyNames()).containsExactlyInAnyOrder(
                "eventId", "orderId", "customerId", "placedAt", "totalAmount", "items");

        assertThat(json.get("items")).hasSize(1);
        assertThat(json.get("items").get(0).propertyNames()).containsExactlyInAnyOrder(
                "productId", "productName", "unitPrice", "quantity");
    }

    @Test
    void theEvent_always_carriesTheValuesAConsumerNeedsWithoutCallingBack() {
        // GIVEN / WHEN
        JsonNode json = MAPPER.valueToTree(sampleEvent());

        // THEN a consumer can write a confirmation from this alone. That is the design rule for an
        // event: if notification-service had to call order-service to find out who the customer was,
        // the broker between them would have decoupled nothing - order-service being down would stop
        // confirmations just as surely as a direct call would.
        assertThat(json.get("orderId").asLong()).isEqualTo(77L);
        assertThat(json.get("customerId").asLong()).isEqualTo(42L);
        assertThat(json.get("totalAmount").decimalValue()).isEqualByComparingTo("259.98");
        assertThat(json.get("items").get(0).get("productName").asString()).isEqualTo("Mechanical Keyboard");
    }

    @Test
    void theEvent_always_timestampsInUtcSoTwoServicesCanCompareIt() {
        // GIVEN / WHEN
        JsonNode json = MAPPER.valueToTree(sampleEvent());

        // THEN placedAt is an unambiguous instant, not a local wall-clock reading. It is written by
        // one service and read by another on a different host; anything zone-dependent would be a
        // bug that only appears when the two are deployed in different regions.
        assertThat(json.get("placedAt").asString()).isEqualTo("2026-09-17T10:15:30Z");
    }

    @Test
    void theEventId_always_differsPerPublication_soItCanIdentifyADelivery() {
        // GIVEN two events built from the same order
        UUID first = sampleEvent().eventId();
        UUID second = sampleEvent().eventId();

        // THEN they differ. The id identifies the EVENT, not the order - it is what lets a consumer
        // recognise a re-delivery of the same record and do nothing the second time. Deriving it
        // from the order id instead would look tidier and would silently merge a genuine second
        // event about the same order with a duplicate of the first.
        assertThat(first).isNotEqualTo(second);
    }
}
