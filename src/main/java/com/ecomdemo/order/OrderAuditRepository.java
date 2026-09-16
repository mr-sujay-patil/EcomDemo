package com.ecomdemo.order;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderAuditRepository extends JpaRepository<OrderAudit, Long> {

    /** Newest attempt first, matching idx_order_audit_occurred_at. */
    List<OrderAudit> findAllByOrderByOccurredAtDescIdDesc();

    List<OrderAudit> findByOutcome(OrderAudit.Outcome outcome);
}
