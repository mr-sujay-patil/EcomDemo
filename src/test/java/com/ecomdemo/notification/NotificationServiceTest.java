package com.ecomdemo.notification;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.ecomdemo.messaging.ProcessedEvent;
import com.ecomdemo.messaging.ProcessedEventRepository;
import com.ecomdemo.order.event.OrderPlacedEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The idempotency rules, which are the whole reason this service exists as a separate class.
 *
 * <p>The {@code Clock} is a real {@code Clock.fixed} rather than a mock: a value-like collaborator
 * with known behaviour and nothing to arrange.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Captor
    private ArgumentCaptor<Notification> notification;

    private NotificationService notificationService;

    private static final Instant NOW = Instant.parse("2026-09-16T10:15:30Z");
    private static final UUID EVENT_ID = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");

    private static final OrderPlacedEvent EVENT = new OrderPlacedEvent(
            EVENT_ID, 7L, 42L, NOW, new BigDecimal("259.98"),
            List.of(new OrderPlacedEvent.Line(1L, "Mechanical Keyboard", new BigDecimal("129.99"), 2)));

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(notificationRepository, processedEventRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Nested
    class SendOrderConfirmation {

        @Test
        void sendOrderConfirmation_whenTheEventIsNew_recordsTheNotification() {
            // GIVEN an event that has not been handled before
            when(processedEventRepository.existsById(EVENT_ID)).thenReturn(false);

            // WHEN
            boolean handled = notificationService.sendOrderConfirmation(EVENT);

            // THEN the confirmation is recorded, carrying what the event said rather than anything
            // looked up
            assertThat(handled).isTrue();
            verify(notificationRepository).save(notification.capture());
            assertThat(notification.getValue().getOrderId()).isEqualTo(7L);
            assertThat(notification.getValue().getCustomerId()).isEqualTo(42L);
            assertThat(notification.getValue().getEventId()).isEqualTo(EVENT_ID);
            assertThat(notification.getValue().getCreatedAt()).isEqualTo(NOW);
            assertThat(notification.getValue().getMessage()).contains("Order #7", "259.98");
        }

        @Test
        void sendOrderConfirmation_whenTheEventIsNew_claimsItFirst() {
            // GIVEN
            when(processedEventRepository.existsById(EVENT_ID)).thenReturn(false);

            // WHEN
            notificationService.sendOrderConfirmation(EVENT);

            // THEN the event is claimed, and flushed rather than merely saved - the insert has to
            // reach the database while it can still be caught, not during the commit afterwards
            ArgumentCaptor<ProcessedEvent> claimed = ArgumentCaptor.forClass(ProcessedEvent.class);
            verify(processedEventRepository).saveAndFlush(claimed.capture());
            assertThat(claimed.getValue().getEventId()).isEqualTo(EVENT_ID);
            assertThat(claimed.getValue().getProcessedAt()).isEqualTo(NOW);
        }

        @Test
        void sendOrderConfirmation_whenTheEventWasAlreadyHandled_writesNothing() {
            // GIVEN the same event delivered a second time - a crash before the offset was
            // committed, or a rebalance. Kafka is at-least-once; this is it behaving correctly.
            when(processedEventRepository.existsById(EVENT_ID)).thenReturn(true);

            // WHEN
            boolean handled = notificationService.sendOrderConfirmation(EVENT);

            // THEN the customer is not told twice, and nothing is written at all
            assertThat(handled).isFalse();
            verify(notificationRepository, never()).save(any());
            verify(processedEventRepository, never()).saveAndFlush(any());
        }

        @Test
        void sendOrderConfirmation_whenTwoDeliveriesRace_writesNothingForTheLoser() {
            // GIVEN a delivery that passes the existsById check because the other delivery has not
            // committed yet, and then loses on the primary key.
            //
            // This is the case the check cannot cover, and the reason the constraint - not the
            // check - is the mechanism.
            when(processedEventRepository.existsById(EVENT_ID)).thenReturn(false);
            when(processedEventRepository.saveAndFlush(any()))
                    .thenThrow(new DataIntegrityViolationException("duplicate key value: processed_events_pkey"));

            // WHEN
            boolean handled = notificationService.sendOrderConfirmation(EVENT);

            // THEN it is absorbed rather than thrown. Throwing would send a perfectly healthy
            // duplicate down the retry topic and eventually into the DLT.
            assertThat(handled).isFalse();
            verify(notificationRepository, never()).save(any());
        }
    }
}
