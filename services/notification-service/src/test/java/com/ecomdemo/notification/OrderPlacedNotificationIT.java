package com.ecomdemo.notification;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.ecomdemo.messaging.KafkaTopics;
import com.ecomdemo.notification.dto.NotificationResponse;
import com.ecomdemo.support.AbstractNotificationServiceIT;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The consumer, end to end: a JSON record goes onto {@code orders.placed} and a confirmation comes
 * out - once, however many times the record arrives.
 *
 * <h2>Why the payloads here are hand-written JSON</h2>
 *
 * This is the single most important decision in this class. The obvious version of these tests builds
 * an {@code OrderPlacedEvent}, hands it to a JSON serializer and publishes it - and proves only that
 * this service can read what this service wrote. What actually has to work is that this service can
 * read what <em>order-service</em> writes, and order-service is a different application with its own
 * copy of that record which nothing here compiles against.
 *
 * <p>So {@link #ORDER_PLACED_JSON} is a golden sample: the exact bytes order-service produces,
 * committed here. order-service has the matching test on its side
 * ({@code OrderPlacedEventContractTest}) asserting that what it serialises still looks like this. The
 * two together are a contract test without a contract-testing framework - cheap, and honest about
 * being a convention rather than a guarantee.
 *
 * <p>Note also what is absent from the JSON: any {@code __TypeId__} header. Real records from
 * order-service carry one naming a class that does not exist in this JVM, which is why
 * {@code spring.json.use.type.headers: false} is set. A consumer that needed the producer's class
 * names would be sharing a jar rather than a message.
 */
@ExtendWith(OutputCaptureExtension.class)
class OrderPlacedNotificationIT extends AbstractNotificationServiceIT {

    @Autowired
    private NotificationRepository notificationRepository;

    /**
     * Exactly what order-service puts on the wire, with the ids substituted per test.
     *
     * <p>Kept as a string rather than built from the record on purpose - see the class comment. If
     * order-service renames a field, this test keeps passing and production breaks, which is
     * precisely the failure mode a shared jar hides and a golden sample makes discussable.
     */
    private static final String ORDER_PLACED_JSON = """
            {
              "eventId": "%s",
              "orderId": %d,
              "customerId": %d,
              "placedAt": "2026-09-17T10:15:30Z",
              "totalAmount": 259.98,
              "items": [
                {"productId": 1, "productName": "Mechanical Keyboard", "unitPrice": 129.99, "quantity": 2}
              ]
            }
            """;

    private String eventFor(UUID eventId, long orderId, long customerId) {
        return ORDER_PLACED_JSON.formatted(eventId, orderId, customerId);
    }

    private void publish(long orderId, String json) {
        // The order id is the message key, exactly as order-service sends it: every event about one
        // order lands in one partition and is therefore ordered relative to the others.
        kafkaTemplate.send(KafkaTopics.ORDERS_PLACED, String.valueOf(orderId), json);
        kafkaTemplate.flush();
    }

    @Nested
    class TheHappyPath {

        @Test
        void aValidEvent_always_producesExactlyOneNotification() {
            // GIVEN an event as order-service would publish it
            UUID eventId = UUID.randomUUID();
            long orderId = customerId;

            // WHEN
            publish(orderId, eventFor(eventId, orderId, customerId));

            // THEN a confirmation is recorded - eventually. There is no synchronous moment when this
            // becomes true, which is the whole difference from a method call: order-service's work is
            // finished and this has not started.
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(notificationRepository.countByEventId(eventId)).isEqualTo(1));
        }

        @Test
        void aValidEvent_always_becomesReadableByTheCustomerItWasSentTo() {
            // GIVEN
            UUID eventId = UUID.randomUUID();
            long orderId = customerId;
            publish(orderId, eventFor(eventId, orderId, customerId));
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(notificationRepository.countByEventId(eventId)).isEqualTo(1));

            // WHEN the customer asks for their confirmations over HTTP
            NotificationResponse[] mine = client.get().uri("/api/notifications")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(NotificationResponse[].class)
                    .returnResult().getResponseBody();

            // THEN it is there, and it is theirs. The customer id came out of the event, and the
            // query scopes by the id in the token - two numbers that must agree, from two different
            // services, with no foreign key between them.
            assertThat(mine).isNotEmpty();
            assertThat(mine[0].orderId()).isEqualTo(orderId);
        }

        @Test
        void readingNotifications_withNoToken_is401() {
            anonymous.get().uri("/api/notifications").exchange().expectStatus().isUnauthorized();
        }
    }

    @Nested
    class Idempotency {

        @Test
        void redeliveringTheSameEvent_always_leavesExactlyOneNotification() {
            // GIVEN one event, published three times with the same eventId - which is what
            // at-least-once delivery looks like from a consumer's seat
            UUID eventId = UUID.randomUUID();
            long orderId = customerId;
            String json = eventFor(eventId, orderId, customerId);

            // WHEN
            publish(orderId, json);
            publish(orderId, json);
            publish(orderId, json);

            // THEN exactly one notification, and it stays exactly one. during() rather than
            // untilAsserted(): the assertion is that the count never reaches two, so the window has
            // to be held open rather than sampled once at the first moment it happens to be right.
            await().atMost(Duration.ofSeconds(20))
                    .until(() -> notificationRepository.countByEventId(eventId) == 1);
            await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10))
                    .until(() -> notificationRepository.countByEventId(eventId) == 1);
        }
    }

    @Nested
    class PoisonMessages {

        @Test
        void aPoisonMessage_always_landsInTheDeadLetterTopicAndIsReadableThere(CapturedOutput output) {
            // GIVEN something that is not JSON at all, tagged so this test can find its own record.
            //
            // The tag is not decoration. Every integration test in this class shares one broker and
            // the dead-letter topic is never drained - that is the whole point of a DLT - so by the
            // time this runs it may already hold another test's poison. Reading "the single record"
            // off it is the topic-shaped version of an IT assuming an empty table, and it failed
            // exactly that way first.
            String marker = "poison-" + System.nanoTime();
            String poison = "{not even valid json " + marker;

            // WHEN
            publish(999_999L, poison);

            // THEN it reaches the dead-letter topic - on the FIRST attempt, not after the retries.
            // A payload that cannot be parsed will not parse in two seconds either, so
            // DeserializationException is non-retryable and skips the retry topics entirely.
            try (Consumer<String, byte[]> dltConsumer = deadLetterConsumer()) {
                ConsumerRecord<String, byte[]> dead = awaitDeadLetterContaining(dltConsumer, marker);

                // The payload is the original text, not base64 and not an exception message. A DLT
                // whose contents cannot be read is a DLT nobody can act on.
                assertThat(new String(dead.value())).isEqualTo(poison);
                assertThat(dead.headers().lastHeader(KafkaHeaders.ORIGINAL_TOPIC)).isNotNull();
            }

            // AND this service's own @DltHandler ran. Asserting only on the topic would pass while
            // the handler silently failed to deserialize the very payload it exists to report - which
            // is exactly what happened in Phase 17 before DelegatingByTopicDeserializer was added.
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(output.getAll()).contains("DEAD LETTER from orders.placed"));
        }

        @Test
        void aPoisonMessage_never_blocksTheRecordsBehindIt() {
            // GIVEN a poison message followed by a good one
            UUID eventId = UUID.randomUUID();
            long orderId = customerId;
            publish(888_888L, "{still not json " + System.nanoTime());
            publish(orderId, eventFor(eventId, orderId, customerId));

            // THEN the good one is processed anyway. This is the head-of-line blocking that
            // @RetryableTopic exists to prevent: a partition is an ordered log read by one consumer,
            // so a listener that sat and retried in place would hold up every customer behind it.
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(notificationRepository.countByEventId(eventId)).isEqualTo(1));
        }
    }

    /**
     * Scans the dead-letter topic from the beginning for the record this test put there.
     *
     * <p>Polls rather than taking the first record, because the topic accumulates across every test
     * in the class and nothing removes anything from it.
     */
    private ConsumerRecord<String, byte[]> awaitDeadLetterContaining(
            Consumer<String, byte[]> consumer, String marker) {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, byte[]> record :
                    KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2))) {
                if (new String(record.value()).contains(marker)) {
                    return record;
                }
            }
        }
        throw new AssertionError("No dead-letter record containing " + marker + " within 30s");
    }

    /**
     * A throwaway consumer that reads the DLT directly.
     *
     * <p>Deserializes to {@code byte[]}, which cannot fail. A JSON deserializer here would break on
     * exactly the records this consumer exists to inspect.
     */
    private Consumer<String, byte[]> deadLetterConsumer() {
        var props = KafkaTestUtils.consumerProps(
                KAFKA.getBootstrapServers(), "dlt-reader-" + UUID.randomUUID(), "true");
        var consumerFactory = new DefaultKafkaConsumerFactory<String, byte[]>(
                props, new StringDeserializer(), new ByteArrayDeserializer());
        Consumer<String, byte[]> consumer = consumerFactory.createConsumer();
        consumer.subscribe(List.of(KafkaTopics.ORDERS_PLACED_DLT));
        return consumer;
    }
}
