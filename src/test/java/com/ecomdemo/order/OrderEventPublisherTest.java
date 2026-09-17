package com.ecomdemo.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.ecomdemo.messaging.KafkaTopics;
import com.ecomdemo.order.dto.OrderItemResponse;
import com.ecomdemo.order.dto.OrderResponse;
import com.ecomdemo.order.event.OrderPlacedEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;

import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the publisher puts on the wire, and what it does when the wire is not there.
 *
 * <p>{@code KafkaTemplate} is mocked rather than backed by an embedded broker: the three things
 * worth pinning here - the topic, the key, and the fact that a broker failure does not escape - are
 * all decisions made on this side of the network. That an event genuinely reaches a consumer is
 * {@code OrderPlacedNotificationIT}'s job, against a real broker.
 */
@ExtendWith(MockitoExtension.class)
class OrderEventPublisherTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Captor
    private ArgumentCaptor<OrderPlacedEvent> event;

    private OrderEventPublisher publisher;

    private static final Long CUSTOMER_ID = 42L;
    private static final Instant PLACED_AT = Instant.parse("2026-09-16T10:15:30Z");

    private static final OrderResponse ORDER = new OrderResponse(
            7L,
            PLACED_AT,
            List.of(new OrderItemResponse(1L, "Mechanical Keyboard", new BigDecimal("129.99"), 2,
                    new BigDecimal("259.98"))),
            new BigDecimal("259.98"));

    @BeforeEach
    void setUp() {
        publisher = new OrderEventPublisher(kafkaTemplate);
    }

    /**
     * A completed send, as the broker would report it.
     *
     * <p>Built rather than stubbed as {@code completedFuture(null)}: the success callback reads the
     * partition and offset off this, and a null result would make that branch throw inside
     * {@code whenComplete} - where the exception is captured by the future and never surfaces. The
     * test would pass while covering nothing.
     */
    private static CompletableFuture<SendResult<String, Object>> acknowledged() {
        TopicPartition partition = new TopicPartition(KafkaTopics.ORDERS_PLACED, 1);
        return CompletableFuture.completedFuture(new SendResult<>(
                new ProducerRecord<>(KafkaTopics.ORDERS_PLACED, "7", ORDER),
                new RecordMetadata(partition, 0L, 0, 0L, 0, 0)));
    }

    @Nested
    class PublishOrderPlaced {

        @Test
        void publishOrderPlaced_always_sendsToOrdersPlacedKeyedByOrderId() {
            // GIVEN a broker that accepts the record
            when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(acknowledged());

            // WHEN
            publisher.publishOrderPlaced(ORDER, CUSTOMER_ID);

            // THEN the key is the order id, as a string - this is what pins every event about one
            // order to one partition, and therefore what makes them ordered relative to each other
            verify(kafkaTemplate).send(eq(KafkaTopics.ORDERS_PLACED), eq("7"), event.capture());
            assertThat(event.getValue().orderId()).isEqualTo(7L);
        }

        @Test
        void publishOrderPlaced_always_carriesEverythingAConsumerNeeds() {
            // GIVEN
            when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(acknowledged());

            // WHEN
            publisher.publishOrderPlaced(ORDER, CUSTOMER_ID);

            // THEN a consumer can act on the event without calling back into this application
            verify(kafkaTemplate).send(anyString(), anyString(), event.capture());
            OrderPlacedEvent published = event.getValue();
            assertThat(published.eventId()).isNotNull();
            assertThat(published.customerId()).isEqualTo(CUSTOMER_ID);
            assertThat(published.placedAt()).isEqualTo(PLACED_AT);
            assertThat(published.totalAmount()).isEqualByComparingTo("259.98");
            assertThat(published.items()).singleElement().satisfies(line -> {
                assertThat(line.productName()).isEqualTo("Mechanical Keyboard");
                assertThat(line.quantity()).isEqualTo(2);
                assertThat(line.unitPrice()).isEqualByComparingTo("129.99");
            });
        }

        @Test
        void publishOrderPlaced_always_generatesAFreshEventIdPerPublication() {
            // GIVEN
            when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(acknowledged());

            // WHEN the same order is published twice
            publisher.publishOrderPlaced(ORDER, CUSTOMER_ID);
            publisher.publishOrderPlaced(ORDER, CUSTOMER_ID);

            // THEN each publication is its own event.
            //
            // This is the line between the two kinds of duplicate. A re-delivery of one record
            // carries one eventId and the consumer's idempotency table absorbs it; publishing twice
            // is two distinct events and the consumer is right to act on both. Sharing an id across
            // publications would make the second order silently vanish.
            verify(kafkaTemplate, times(2)).send(anyString(), anyString(), event.capture());
            assertThat(event.getAllValues().get(0).eventId())
                    .isNotEqualTo(event.getAllValues().get(1).eventId());
        }

        @Test
        void publishOrderPlaced_whenTheBrokerIsUnreachable_doesNotThrow() {
            // GIVEN a send that fails synchronously - no cluster metadata within max.block.ms
            when(kafkaTemplate.send(anyString(), anyString(), any()))
                    .thenThrow(new KafkaException("no brokers available"));

            // WHEN / THEN the checkout that called this has already committed. Throwing would turn a
            // completed purchase into a 500 while leaving the order in the database.
            assertThatCode(() -> publisher.publishOrderPlaced(ORDER, CUSTOMER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        void publishOrderPlaced_whenTheAcknowledgementFails_doesNotThrow() {
            // GIVEN a send that is accepted and then fails asynchronously
            CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
            failed.completeExceptionally(new KafkaException("not enough in-sync replicas"));
            when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(failed);

            // WHEN / THEN the failure arrives on a producer thread, with no caller left to tell
            assertThatCode(() -> publisher.publishOrderPlaced(ORDER, CUSTOMER_ID))
                    .doesNotThrowAnyException();
        }
    }
}
