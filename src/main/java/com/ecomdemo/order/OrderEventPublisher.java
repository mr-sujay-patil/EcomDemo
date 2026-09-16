package com.ecomdemo.order;

import com.ecomdemo.messaging.KafkaTopics;
import com.ecomdemo.order.dto.OrderResponse;
import com.ecomdemo.order.event.OrderPlacedEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link OrderPlacedEvent} to Kafka after a checkout has committed.
 *
 * <h2>The message key is the order id</h2>
 *
 * The producer chooses a partition by hashing the key, so every record about one order goes to one
 * partition - and a partition is the only place Kafka promises ordering. Keying by order id buys
 * per-order ordering while still spreading different orders across all three partitions. A null key
 * would round-robin, and any future {@code orders.cancelled} event could then overtake the
 * {@code orders.placed} it cancels.
 *
 * <h2>A failure here must not fail the checkout</h2>
 *
 * By the time this is called the transaction has committed: the order exists, stock is reduced, the
 * cart is empty and the customer has been charged in every sense that matters to them. Throwing now
 * would turn a successful purchase into a 500 while leaving the order in the database - the worst of
 * both. So the exception is logged and swallowed.
 *
 * <h2>Which leaves a real hole, and it is worth naming</h2>
 *
 * This is a <strong>dual write</strong>: two systems are changed by one logical operation, with no
 * transaction spanning them. If the send fails - broker down, network gone, process killed between
 * the commit and this line - the order is in PostgreSQL and the event is nowhere, and nothing will
 * ever reconcile the two. No amount of retrying inside this method closes it, because the process
 * can die before the retry.
 *
 * <p>It cannot be fixed here. The fix is to write the event to the same database, in the same
 * transaction as the order, and have something else move it to Kafka afterwards - the transactional
 * outbox, which is Phase 18 and the reason this phase leaves the gap visible rather than papering
 * over it.
 */
@Component
public class OrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public OrderEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishOrderPlaced(OrderResponse order, Long customerId) {
        OrderPlacedEvent event = OrderPlacedEvent.from(order, customerId);
        String key = String.valueOf(event.orderId());

        try {
            // send() returns a future and does not wait for the broker to acknowledge. It can still
            // block on this thread for up to max.block.ms while the client fetches cluster metadata
            // for a topic it has not written to before, which is why that timeout is lowered from
            // its 60 second default in application.yml.
            kafkaTemplate.send(KafkaTopics.ORDERS_PLACED, key, event)
                    .whenComplete((result, failure) -> {
                        if (failure != null) {
                            // The acknowledgement failed after the send was handed off, so this runs
                            // on a producer thread and there is no caller left to tell.
                            log.error("orders.placed NOT published for order {} (eventId {}) - "
                                            + "the order is committed but no consumer will hear about it",
                                    event.orderId(), event.eventId(), failure);
                        } else {
                            log.info("orders.placed published: order={} eventId={} partition={} offset={}",
                                    event.orderId(), event.eventId(),
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });
        } catch (RuntimeException ex) {
            // Thrown synchronously - no metadata within max.block.ms, or serialization failed.
            log.error("orders.placed could not be sent for order {} (eventId {})",
                    event.orderId(), event.eventId(), ex);
        }
    }
}
