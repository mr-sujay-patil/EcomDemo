package com.ecomdemo.notification;

import java.time.Clock;
import java.util.List;

import com.ecomdemo.messaging.ProcessedEvent;
import com.ecomdemo.messaging.ProcessedEventRepository;
import com.ecomdemo.notification.dto.NotificationResponse;
import com.ecomdemo.order.event.OrderPlacedEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns an {@code orders.placed} event into a confirmation, at most once.
 *
 * <h2>The idempotency, in three parts</h2>
 *
 * <ol>
 *   <li><strong>One transaction.</strong> The {@code processed_events} row and the notification are
 *       written together or not at all. Two separate commits would let the process die between them
 *       and leave an event marked handled that was not, which is the one failure mode worse than a
 *       duplicate.
 *   <li><strong>The claim comes first.</strong> Inserting into {@code processed_events} is what
 *       reserves the event. Doing the work first and recording it afterwards leaves the same window
 *       open in the other direction.
 *   <li><strong>The primary key is the guard, not the {@code existsById}.</strong> The check is an
 *       optimisation - it answers the common case cheaply, and it is why a re-delivery does not
 *       produce a stack trace. It cannot be the mechanism: between the read and the write there is
 *       a window, and two consumers racing after a rebalance would both pass. The unique constraint
 *       has no window, which is why the catch below is the part that must not be removed.
 * </ol>
 *
 * <p>Note what this does <em>not</em> promise. Exactly-once processing across a broker and a
 * database is not available - the work and the offset commit are two systems again - but
 * exactly-once <em>effect</em> is, and that is what a customer cares about: they get one email, not
 * two, however many times Kafka delivers the record.
 */
@Service
@Transactional(readOnly = true)
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private static final String EVENT_TYPE = "OrderPlacedEvent";

    private final NotificationRepository notificationRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final Clock clock;

    public NotificationService(NotificationRepository notificationRepository,
                               ProcessedEventRepository processedEventRepository,
                               Clock clock) {
        this.notificationRepository = notificationRepository;
        this.processedEventRepository = processedEventRepository;
        this.clock = clock;
    }

    /**
     * Records the confirmation for an order, unless this event has already been handled.
     *
     * @return true if this call did the work, false if the event was a duplicate
     */
    @Transactional
    public boolean sendOrderConfirmation(OrderPlacedEvent event) {
        if (processedEventRepository.existsById(event.eventId())) {
            log.info("Skipping duplicate delivery of {} {} for order {} - already handled",
                    EVENT_TYPE, event.eventId(), event.orderId());
            return false;
        }

        try {
            // Claim the event and do the work in one transaction. saveAndFlush, not save: the insert
            // has to reach the database now so the constraint can reject a racing duplicate here,
            // where it can be caught, rather than during the commit that happens after this method
            // returns - the same reasoning as the flush in OrderPlacement.
            processedEventRepository.saveAndFlush(
                    new ProcessedEvent(event.eventId(), EVENT_TYPE, clock.instant()));
        } catch (DataIntegrityViolationException alreadyClaimed) {
            // Another delivery of this event got the row first. Not an error - this is the
            // at-least-once guarantee behaving exactly as documented.
            log.info("Lost the race to claim {} {} for order {} - another delivery is handling it",
                    EVENT_TYPE, event.eventId(), event.orderId());
            return false;
        }

        String message = confirmationMessage(event);
        notificationRepository.save(new Notification(
                event.eventId(), event.orderId(), event.customerId(), message, clock.instant()));

        // The "send". A real implementation would hand this to an email or SMS provider - which is
        // a third system, and therefore another dual write, and therefore not this phase.
        log.info("NOTIFICATION to customer {}: {}", event.customerId(), message);
        return true;
    }

    @PreAuthorize("#customerId == authentication.principal.id")
    public List<NotificationResponse> findAll(Long customerId) {
        return notificationRepository.findAllByCustomerIdOrderByCreatedAtDescIdDesc(customerId).stream()
                .map(NotificationResponse::from)
                .toList();
    }

    private static String confirmationMessage(OrderPlacedEvent event) {
        int lines = event.items() == null ? 0 : event.items().size();
        return "Thank you! Order #" + event.orderId() + " is confirmed - "
                + lines + " line(s), total " + event.totalAmount() + ".";
    }
}
