package com.ecomdemo.order;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import com.ecomdemo.cart.CartItem;
import com.ecomdemo.cart.CartService;
import com.ecomdemo.order.client.CatalogProduct;
import com.ecomdemo.order.dto.OrderResponse;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The local half of checkout: the order row, its lines, and emptying the cart - in one transaction.
 *
 * <h2>Why this is its own bean</h2>
 *
 * {@link OrderPlacement} must <em>not</em> be transactional: it makes two HTTP calls, and holding a
 * database transaction open across a network call is a reliable way to exhaust the connection pool -
 * a connection held for the duration of somebody else's latency. So the transaction has to start
 * after those calls and cover the database work alone.
 *
 * <p>Putting this method on {@code OrderPlacement} and calling it from {@code placeOnce} would not
 * achieve that. Spring applies {@code @Transactional} with a proxy, and a {@code this.saveOrder(...)}
 * call goes straight to the method without passing through it - so the annotation would be ignored,
 * the code would run anyway, nothing would be logged, and the order, its lines and the cart clear
 * would each commit separately. A half-written order is exactly what this class exists to prevent, so
 * the boundary is made real with a second bean rather than left to a comment.
 *
 * <p>This is the third time the same rule has shaped this feature: {@code OrderService} and
 * {@code OrderPlacement} are separate so the retry sits outside the transaction, {@code
 * OrderAuditService} is separate so its writes can outlive a rollback, and this is separate so the
 * transaction can start after the remote calls. CLAUDE.md states it once; the package demonstrates it
 * three times.
 *
 * <p>The method must stay public: Spring's proxies ignore non-public methods.
 */
@Component
class OrderWriter {

    private final OrderRepository orderRepository;
    private final CartService cartService;
    private final OrderAuditService orderAuditService;
    private final Clock clock;

    OrderWriter(OrderRepository orderRepository,
                CartService cartService,
                OrderAuditService orderAuditService,
                Clock clock) {
        this.orderRepository = orderRepository;
        this.cartService = cartService;
        this.orderAuditService = orderAuditService;
        this.clock = clock;
    }

    /**
     * Writes the order and empties the cart, or does neither.
     *
     * <p>The prices come in as a parameter - already fetched from catalog-service - and are copied
     * into each line. Nothing here calls out to another service, which is what makes it safe to wrap
     * in a transaction.
     */
    @Transactional
    public OrderResponse saveOrder(Long customerId,
                                   List<Long> productIds,
                                   List<Integer> quantities,
                                   Map<Long, CatalogProduct> products) {
        Order order = new Order(customerId, clock.instant());
        for (int line = 0; line < productIds.size(); line++) {
            order.addItem(new OrderItem(order, products.get(productIds.get(line)), quantities.get(line)));
        }
        Order saved = orderRepository.save(order);

        // The cart has become the order, so it is emptied. Re-read inside this transaction: the
        // instance OrderPlacement loaded belongs to a transaction that has already closed, and
        // mutating a detached entity writes nothing.
        cartService.requireCart(customerId).clear();

        // Force the writes out now rather than at commit, so a failure happens inside this method
        // where it can be audited and re-thrown deliberately, rather than while the proxy commits.
        try {
            orderRepository.flush();
        } catch (OptimisticLockingFailureException ex) {
            orderAuditService.recordAttempt(OrderAudit.Outcome.CONCURRENT_MODIFICATION,
                    "Another transaction changed this data first: " + ex.getClass().getSimpleName(), null);
            throw ex;
        }

        orderAuditService.recordAttempt(OrderAudit.Outcome.PLACED,
                "Order placed with " + saved.getItems().size() + " line(s), total " + saved.getTotalAmount(),
                saved.getId());

        return OrderResponse.from(saved);
    }

    /** The lines of a cart, flattened into the two parallel lists {@link #saveOrder} takes. */
    static List<Long> productIdsOf(List<CartItem> cartItems) {
        return cartItems.stream().map(CartItem::getProductId).toList();
    }

    static List<Integer> quantitiesOf(List<CartItem> cartItems) {
        return cartItems.stream().map(CartItem::getQuantity).toList();
    }
}
