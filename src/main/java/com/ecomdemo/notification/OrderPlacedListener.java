package com.ecomdemo.notification;

import com.ecomdemo.messaging.KafkaTopics;
import com.ecomdemo.order.event.OrderPlacedEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code orders.placed} and asks {@link NotificationService} to confirm the order.
 *
 * <h2>What retries look like on Kafka</h2>
 *
 * Retrying in place is not an option. A partition is an ordered log read by one consumer, so a
 * listener that sleeps and tries again blocks every record behind it - one order with a deadlocked
 * row would hold up every other customer's confirmation. That is called head-of-line blocking, and
 * it is why {@code @RetryableTopic} moves the failed record <em>off</em> the partition instead:
 *
 * <pre>
 *   orders.placed  --fails-->  orders.placed-retry  --fails again-->  orders.placed-dlt
 * </pre>
 *
 * The main partition keeps moving immediately. The price is that a retried record is no longer in
 * order relative to the others, which is the trade-off being made here and is fine for a
 * notification. It would not be fine for something that applies state transitions in sequence.
 *
 * <h2>A poison message goes straight to the dead-letter topic</h2>
 *
 * Retrying only helps a failure that might not happen again - a deadlock, a timeout, a service that
 * was briefly down. A message that cannot be deserialized will fail identically forever, so
 * {@code DeserializationException} is on Spring's non-retryable list and is routed to the DLT on the
 * first attempt. Retrying it three times would only mean waiting longer to reach the same place.
 *
 * <p>That only works because {@code ErrorHandlingDeserializer} is configured in
 * {@code application.yml}. Without it the failure happens inside the poll, before any of this
 * machinery is reached, and the partition stops advancing forever.
 *
 * <h2>The dead-letter topic is a queue for humans</h2>
 *
 * Nothing drains it automatically. Its purpose is that a record which cannot be processed is kept,
 * visibly, instead of being dropped or blocking the topic - somebody looks at it, fixes the cause,
 * and replays it.
 */
@Component
public class OrderPlacedListener {

    private static final Logger log = LoggerFactory.getLogger(OrderPlacedListener.class);

    private final NotificationService notificationService;

    public OrderPlacedListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * @param event the deserialized event. A failure here is what drives the retry/DLT machinery,
     *              so this method must throw on failure rather than catch and log - swallowing an
     *              exception commits the offset and loses the record.
     */
    @RetryableTopic(
            // Three attempts in total, not three retries after the first.
            attempts = "3",
            // 1s, then 2s. Exponential because whatever is broken is usually either better almost
            // immediately or not better for a while; hammering it at a fixed interval helps neither
            // case. Kept short so the integration test does not have to wait out a real backoff.
            //
            // Note the annotation: org.springframework.kafka.annotation.BackOff, and the attribute
            // is backOff. Spring Kafka 4.1 dropped its dependency on the separate Spring Retry
            // project and brought its own, exactly as Spring Framework 7 did for the @Retryable on
            // OrderService. Every example written before that uses @Backoff from
            // org.springframework.retry.annotation, which is no longer on the classpath.
            backOff = @BackOff(delay = 1000, multiplier = 2.0),
            // One retry topic shared by both attempts rather than -retry-0 and -retry-1. Fewer
            // topics to look at, at the cost of not being able to tell at a glance which attempt a
            // record is on.
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            retryTopicSuffix = "-retry",
            dltTopicSuffix = "-dlt")
    @KafkaListener(topics = KafkaTopics.ORDERS_PLACED, groupId = "ecomdemo-notifications")
    public void onOrderPlaced(OrderPlacedEvent event) {
        log.info("Received orders.placed: order={} eventId={}", event.orderId(), event.eventId());
        notificationService.sendOrderConfirmation(event);
    }

    /**
     * The end of the line: a record that failed every attempt, or one that was never valid.
     *
     * <p>This method must not throw. A dead-letter topic has nowhere to forward to, so an exception
     * here means the record is re-delivered to this handler indefinitely.
     *
     * <p>It takes the raw bytes rather than an {@code OrderPlacedEvent}, because the most common
     * reason to be here is that the payload could not become one.
     */
    @DltHandler
    public void onDeadLetter(byte[] payload,
                             @Header(KafkaHeaders.ORIGINAL_TOPIC) String originalTopic,
                             @Header(name = KafkaHeaders.EXCEPTION_MESSAGE, required = false) String reason) {
        log.error("DEAD LETTER from {}: {} - payload: {}",
                originalTopic, reason, new String(payload));
    }
}
