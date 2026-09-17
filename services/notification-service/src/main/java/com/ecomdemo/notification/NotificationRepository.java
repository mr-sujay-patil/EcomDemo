package com.ecomdemo.notification;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** This customer's confirmations, newest first - what GET /api/notifications returns. */
    List<Notification> findAllByCustomerIdOrderByCreatedAtDescIdDesc(Long customerId);

    /**
     * How many notifications exist for one event.
     *
     * <p>Exists for the tests: "recorded once" is a claim about a count, and a test that asserted
     * only that a row exists would pass just as happily with three.
     */
    long countByEventId(UUID eventId);
}
