package com.ecomdemo.order;

import java.util.List;

import com.ecomdemo.shared.ConflictException;
import com.ecomdemo.shared.NotFoundException;
import com.ecomdemo.order.dto.OrderResponse;

import io.micrometer.core.instrument.Timer;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The order API the rest of the application calls.
 *
 * <p>Reads run in a read-only transaction (the class-level annotation). Checkout does not run in a
 * transaction at all - it retries one, which is a different job and has to happen outside.
 *
 * <p>Since Phase 17 a successful checkout also publishes an {@code orders.placed} event. That
 * happens here rather than in {@link OrderPlacement} so that it happens after the commit - see
 * {@link OrderEventPublisher} for why, and for what that still does not guarantee.
 */
@Service
@Transactional(readOnly = true)
public class OrderService {

    /**
     * Two retries, so three attempts in total. Small on purpose: a version conflict means someone
     * else got there first, and if that keeps happening the honest answer to the caller is 409, not
     * an ever-longer wait. Retrying forever under contention is how a slow endpoint becomes an
     * outage.
     */
    static final long MAX_RETRIES = 2;

    private final OrderRepository orderRepository;
    private final OrderPlacement orderPlacement;
    private final OrderMetrics orderMetrics;
    private final OrderEventPublisher orderEventPublisher;

    public OrderService(OrderRepository orderRepository,
                        OrderPlacement orderPlacement,
                        OrderMetrics orderMetrics,
                        OrderEventPublisher orderEventPublisher) {
        this.orderRepository = orderRepository;
        this.orderPlacement = orderPlacement;
        this.orderMetrics = orderMetrics;
        this.orderEventPublisher = orderEventPublisher;
    }

    /**
     * Places an order, retrying if another transaction changed a product first.
     *
     * <p>{@code @Retryable} comes from Spring Framework 7 itself
     * ({@code org.springframework.resilience}), not the separate Spring Retry project - the
     * declarative form with no extra dependency.
     *
     * <p>Three things about this method are deliberate and easy to get wrong:
     *
     * <ul>
     *   <li>It delegates to {@link OrderPlacement}, a <em>different bean</em>. Calling a
     *       {@code @Transactional} method on {@code this} would bypass the proxy and run with no
     *       transaction at all, silently.
     *   <li>{@link Propagation#NEVER} opts out of the class-level {@code readOnly = true}. A
     *       class-level {@code @Transactional} applies to every method, so without this the retry
     *       would run inside a read-only transaction that {@code OrderPlacement} would then join -
     *       and a rolled-back transaction cannot be retried from inside itself. {@code NEVER} turns
     *       "no transaction here" from a hope into an assertion that fails loudly.
     *   <li>Retries are bounded. When they run out the {@code OptimisticLockingFailureException}
     *       propagates and {@code GlobalExceptionHandler} answers 409 - the request was reasonable,
     *       the current state would not allow it.
     * </ul>
     */
    @Retryable(includes = OptimisticLockingFailureException.class, maxRetries = MAX_RETRIES,
            delay = 25, jitter = 25)
    @Transactional(propagation = Propagation.NEVER)
    @PreAuthorize("#customerId == authentication.principal.id")
    public OrderResponse placeOrder(Long customerId) {
        /*
         * The metrics live here, in the non-transactional method, and not in OrderPlacement.
         *
         * A meter has no rollback hook. Incrementing orders.placed inside the transaction would
         * count an order that a later exception erased, and nothing would ever correct it - the
         * number would drift upward forever. By the time placeOnce() returns, the transaction has
         * committed and the order exists.
         *
         * The cost of putting it here is that this method body runs once per retry attempt, so the
         * timer sees one sample per attempt rather than one per request. That is deliberate: the
         * conflict rate is exactly what this phase wants to be able to see.
         */
        Timer.Sample sample = orderMetrics.startCheckout();
        try {
            OrderResponse order = orderPlacement.placeOnce(customerId);
            orderMetrics.recordCheckout(sample, OrderMetrics.OUTCOME_SUCCESS);
            orderMetrics.recordPlacedOrder(order.totalAmount());

            /*
             * The event goes out here, for the same reason the meters are here: placeOnce() has
             * returned, so its transaction has committed and the order genuinely exists. Publishing
             * from inside OrderPlacement would announce orders that a later rollback erased, and
             * Kafka has no rollback - the retraction would have to be a second event that every
             * consumer had to know how to handle.
             *
             * It is still a dual write, and the window is now the other way round: the order can be
             * committed and the event lost. OrderEventPublisher documents that, and Phase 18's
             * outbox is what closes it.
             */
            orderEventPublisher.publishOrderPlaced(order, customerId);
            return order;
        } catch (ConflictException | OptimisticLockingFailureException ex) {
            // The request was well-formed and the server's state refused it - an empty cart, a sold
            // out line, or a lost race. Business outcomes, not faults, and worth their own tag.
            orderMetrics.recordCheckout(sample, OrderMetrics.OUTCOME_CONFLICT);
            throw ex;
        } catch (RuntimeException ex) {
            orderMetrics.recordCheckout(sample, OrderMetrics.OUTCOME_ERROR);
            throw ex;
        }
    }

    /**
     * This customer's orders, newest first.
     *
     * <p>{@code @PreAuthorize} asserts that the id being asked about is the caller's own. The query
     * is already scoped, so this is belt and braces - but it is the belt that survives a future
     * controller passing the wrong id, and it fails before a single row is read rather than after.
     */
    @PreAuthorize("#customerId == authentication.principal.id")
    public List<OrderResponse> findAll(Long customerId) {
        return orderRepository.findAllByCustomerWithItems(customerId).stream()
                .map(OrderResponse::from)
                .toList();
    }

    /**
     * One of this customer's orders.
     *
     * <p>Someone else's order id produces <strong>404, not 403</strong>. 403 would confirm that the
     * order exists, which turns sequential ids into a way to count the shop's orders and probe for
     * which ones are real. As far as this customer is concerned, an order that is not theirs does not
     * exist - and the scoped query means the row is never even loaded.
     */
    @PreAuthorize("#customerId == authentication.principal.id")
    public OrderResponse findById(Long id, Long customerId) {
        return orderRepository.findByIdAndCustomerWithItems(id, customerId)
                .map(OrderResponse::from)
                .orElseThrow(() -> NotFoundException.of("Order", id));
    }
}
