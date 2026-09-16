package com.ecomdemo.notification;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartItemResponse;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.messaging.KafkaTopics;
import com.ecomdemo.notification.dto.NotificationResponse;
import com.ecomdemo.order.dto.OrderResponse;
import com.ecomdemo.order.event.OrderPlacedEvent;
import com.ecomdemo.product.dto.ProductRequest;
import com.ecomdemo.product.dto.ProductResponse;
import com.ecomdemo.support.AbstractPostgresIT;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The Phase 17 "done when", end to end: a real broker, a real database, a real HTTP request.
 *
 * <p>Everything here is asynchronous, so every assertion is an {@code await().untilAsserted(...)}
 * rather than a sleep. A fixed sleep is either longer than it needs to be on a fast machine or too
 * short on a loaded one, and the second failure mode is the one that reaches CI.
 *
 * <p>Like every IT in this project it shares one container with the rest of the run and commits as
 * it goes, so nothing here asserts on a count of all rows - each test creates what it needs and
 * asserts on that.
 */
@ExtendWith(OutputCaptureExtension.class)
class OrderPlacedNotificationIT extends AbstractPostgresIT {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private NotificationRepository notificationRepository;

    private static final ParameterizedTypeReference<List<NotificationResponse>> NOTIFICATION_LIST =
            new ParameterizedTypeReference<>() {
            };

    @BeforeEach
    void emptyTheCart() {
        for (CartItemResponse item : getCart().items()) {
            client.delete().uri("/api/cart/items/" + item.productId()).exchange().expectStatus().isOk();
        }
    }

    private CartResponse getCart() {
        return client.get().uri("/api/cart").exchange()
                .expectStatus().isOk()
                .expectBody(CartResponse.class)
                .returnResult().getResponseBody();
    }

    private ProductResponse newProduct(String price, int stock) {
        return admin.post().uri("/api/products")
                .body(new ProductRequest("IT Notification " + System.nanoTime(), "for the Kafka IT",
                        new BigDecimal(price), stock, null))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(ProductResponse.class)
                .returnResult().getResponseBody();
    }

    private OrderResponse placeAnOrder(String price, int quantity) {
        ProductResponse product = newProduct(price, 50);
        client.post().uri("/api/cart/items").body(new AddCartItemRequest(product.id(), quantity))
                .exchange().expectStatus().isOk();
        return client.post().uri("/api/orders")
                .exchange()
                .expectStatus().isCreated()
                .expectBody(OrderResponse.class)
                .returnResult().getResponseBody();
    }

    @Test
    void placingAnOrder_always_producesAnEventAndRecordsExactlyOneNotification() {
        // GIVEN / WHEN an order is placed over HTTP
        OrderResponse order = placeAnOrder("129.99", 2);

        // THEN the consumer eventually writes the confirmation.
        //
        // "Eventually" is the point of this phase. The POST returned as soon as the order was
        // committed; the notification is produced by a different thread, reading from a broker,
        // after the response was already on its way back. Nothing in the request path waited for it.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(findNotificationsForOrder(order.id()))
                        .singleElement()
                        .satisfies(notification -> assertThat(notification.message())
                                .contains("Order #" + order.id(), "259.98")));

        // AND the customer can read it back over HTTP
        List<NotificationResponse> mine = client.get().uri("/api/notifications")
                .exchange()
                .expectStatus().isOk()
                .expectBody(NOTIFICATION_LIST)
                .returnResult().getResponseBody();
        assertThat(mine).extracting(NotificationResponse::orderId).contains(order.id());
    }

    @Test
    void redeliveringTheSameEvent_always_leavesExactlyOneNotification() {
        // GIVEN an order whose event has already been consumed
        OrderResponse order = placeAnOrder("50.00", 1);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(findNotificationsForOrder(order.id())).hasSize(1));

        UUID eventId = notificationRepository
                .findAllByCustomerIdOrderByCreatedAtDescIdDesc(customerId).stream()
                .filter(n -> n.getOrderId().equals(order.id()))
                .findFirst().orElseThrow()
                .getEventId();

        // WHEN the identical event is delivered twice more - which is exactly what Kafka does after
        // a consumer crashes between doing its work and committing its offset, or after a rebalance
        OrderPlacedEvent redelivered = new OrderPlacedEvent(eventId, order.id(), customerId,
                order.placedAt(), order.totalAmount(), List.of());
        kafkaTemplate.send(KafkaTopics.ORDERS_PLACED, String.valueOf(order.id()), redelivered);
        kafkaTemplate.send(KafkaTopics.ORDERS_PLACED, String.valueOf(order.id()), redelivered);

        // THEN the customer is still told once.
        //
        // Asserted with during() rather than until(): "it never became 2" is a claim that has to
        // hold for a while, and untilAsserted would pass on its very first evaluation, before the
        // duplicates had even been consumed.
        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(notificationRepository.countByEventId(eventId)).isEqualTo(1));
    }

    @Test
    void aPoisonMessage_always_landsInTheDeadLetterTopic(CapturedOutput output) {
        // GIVEN a consumer parked on the dead-letter topic before anything is sent to it
        try (Consumer<String, byte[]> dlt = deadLetterConsumer()) {

            // WHEN something that is not an OrderPlacedEvent is published to the main topic, the way
            // a misconfigured producer or a hand-typed kafka-console-producer would
            rawKafkaTemplate().send(KafkaTopics.ORDERS_PLACED, "999", "{not even valid json".getBytes());

            // THEN it arrives in the DLT.
            //
            // Two things make this work, and both are easy to lose. ErrorHandlingDeserializer turns
            // the failure into a failed *record* rather than a failed poll - without it the
            // partition would stop here forever and nothing would reach the DLT at all. And
            // DeserializationException is non-retryable, so it skips the retry topic: a payload that
            // cannot be parsed will not parse in two seconds either.
            ConsumerRecords<String, byte[]> records =
                    KafkaTestUtils.getRecords(dlt, Duration.ofSeconds(30), 1);

            assertThat(records.records(KafkaTopics.ORDERS_PLACED_DLT))
                    .anySatisfy(record -> {
                        // The payload is readable as it was sent, not base64 inside a JSON string.
                        // That is what retryKafkaTemplate's DelegatingByTypeSerializer buys, and it
                        // is the difference between a DLT you can read in Kafka UI and one you have
                        // to decode first.
                        assertThat(new String(record.value())).isEqualTo("{not even valid json");

                        // AND it says why it is here. The headers are the whole point of a DLT
                        // record: the payload alone does not tell you what went wrong.
                        //
                        // EXCEPTION_FQCN, not DLT_EXCEPTION_FQCN. KafkaHeaders defines both
                        // families, and @RetryableTopic's recoverer writes the un-prefixed one -
                        // the same headers travel with a record through the retry topics, where
                        // "dlt" would be a lie. Reaching for the DLT_ constant because the record
                        // is in the DLT gets a null back and an NPE, not a failed assertion.
                        assertThat(new String(record.headers()
                                .lastHeader(KafkaHeaders.EXCEPTION_FQCN).value()))
                                .isEqualTo(DeserializationException.class.getName());
                        assertThat(new String(record.headers()
                                .lastHeader(KafkaHeaders.ORIGINAL_TOPIC).value()))
                                .isEqualTo(KafkaTopics.ORDERS_PLACED);
                    });

            // AND the application's own @DltHandler ran.
            //
            // Asserted separately, and not redundantly. The check above uses a consumer this test
            // built, and it passed while OrderPlacedListener.onDeadLetter was silently never called:
            // the DLT container deserialized with Jackson like every other topic, so it failed on
            // the one payload it exists to report. Reading the topic proves the record arrived;
            // only this proves anyone noticed.
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(output.getAll())
                            .contains("DEAD LETTER from " + KafkaTopics.ORDERS_PLACED)
                            .contains("{not even valid json"));
        }
    }

    @Test
    void aValidEvent_always_carriesTheOrderIdAsItsKey() {
        // GIVEN a consumer on the main topic
        try (Consumer<String, byte[]> main = topicConsumer(KafkaTopics.ORDERS_PLACED, "it-key-check")) {

            // WHEN an order is placed
            OrderResponse order = placeAnOrder("19.99", 1);

            // THEN the record is keyed by the order id - which is what pins every event about one
            // order to one partition, and is therefore the whole ordering guarantee
            ConsumerRecords<String, byte[]> records =
                    KafkaTestUtils.getRecords(main, Duration.ofSeconds(30), 1);
            assertThat(records.records(KafkaTopics.ORDERS_PLACED))
                    .extracting(ConsumerRecord::key)
                    .contains(String.valueOf(order.id()));
        }
    }

    private List<NotificationResponse> findNotificationsForOrder(Long orderId) {
        return notificationRepository.findAllByCustomerIdOrderByCreatedAtDescIdDesc(customerId).stream()
                .filter(notification -> notification.getOrderId().equals(orderId))
                .map(NotificationResponse::from)
                .toList();
    }

    /**
     * A template that sends raw bytes, for publishing something the application's own serializer
     * would never produce.
     */
    private KafkaTemplate<String, byte[]> rawKafkaTemplate() {
        Map<String, Object> props = KafkaTestUtils.producerProps(KAFKA.getBootstrapServers());
        // Both, explicitly: KafkaTestUtils defaults the key serializer to IntegerSerializer, which
        // fails on a String key with a message about the wrong class rather than the wrong config.
        props.put("key.serializer", org.apache.kafka.common.serialization.StringSerializer.class);
        props.put("value.serializer", org.apache.kafka.common.serialization.ByteArraySerializer.class);
        return new KafkaTemplate<>(new org.springframework.kafka.core.DefaultKafkaProducerFactory<>(props));
    }

    private Consumer<String, byte[]> deadLetterConsumer() {
        return topicConsumer(KafkaTopics.ORDERS_PLACED_DLT, "it-dlt-reader");
    }

    /** A throwaway consumer in its own group, so it sees the topic from the beginning and competes with nobody. */
    private Consumer<String, byte[]> topicConsumer(String topic, String group) {
        Map<String, Object> props = KafkaTestUtils.consumerProps(
                KAFKA.getBootstrapServers(), group + "-" + System.nanoTime(), "true");
        props.put("key.deserializer", StringDeserializer.class);
        props.put("value.deserializer", ByteArrayDeserializer.class);
        props.put("auto.offset.reset", "earliest");
        Consumer<String, byte[]> consumer =
                new DefaultKafkaConsumerFactory<String, byte[]>(props).createConsumer();
        consumer.subscribe(List.of(topic));
        return consumer;
    }
}
