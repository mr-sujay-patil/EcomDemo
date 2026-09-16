package com.ecomdemo.order;

import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the audit trail in a transaction of its own.
 *
 * <p>{@link Propagation#REQUIRES_NEW} is the entire point of this class. It suspends whatever
 * transaction the caller is in, runs this method in a brand new one, commits it, and only then
 * resumes the caller. So when the checkout that called it rolls back - out of stock, empty cart,
 * version conflict - the order and the stock changes disappear and this row stays.
 *
 * <p>With the default {@code REQUIRED} the method would simply join the caller's transaction and the
 * audit row would roll back along with everything else, which is worse than not auditing at all:
 * the trail would be silently complete only for the cases that already worked.
 *
 * <p>The cost is real and worth knowing. A suspended transaction still holds its locks and its
 * connection while the new one runs, so the pool must have room for both - this is why
 * {@code maximum-pool-size} in {@code application-dev.yml} cannot be 1. Two connections are in play
 * for every audited attempt.
 *
 * <p>This is also a separate bean rather than a method on {@code OrderPlacement} for a reason
 * Spring makes unforgiving: transactions are applied by a proxy, so an internal {@code this.audit()}
 * call never passes through it and {@code REQUIRES_NEW} would be silently ignored. The audit would
 * then join the caller's transaction and vanish on rollback - with no error anywhere. Keeping it in
 * another bean makes that mistake impossible rather than merely documented.
 */
@Service
@Transactional(readOnly = true)
public class OrderAuditService {

    private final OrderAuditRepository orderAuditRepository;
    private final Clock clock;

    public OrderAuditService(OrderAuditRepository orderAuditRepository, Clock clock) {
        this.orderAuditRepository = orderAuditRepository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(OrderAudit.Outcome outcome, String detail, Long orderId) {
        orderAuditRepository.save(new OrderAudit(clock.instant(), outcome, truncate(detail), orderId));
    }

    public List<OrderAudit> findAll() {
        return orderAuditRepository.findAllByOrderByOccurredAtDescIdDesc();
    }

    /**
     * The detail column is 500 characters. An exception message is not under our control, and an
     * audit write that fails because the message was long would lose the very record it was trying
     * to keep.
     */
    private static String truncate(String detail) {
        return detail.length() <= 500 ? detail : detail.substring(0, 497) + "...";
    }
}
