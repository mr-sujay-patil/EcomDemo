package com.ecomdemo.order;

import java.util.List;

import com.ecomdemo.cart.Cart;
import com.ecomdemo.cart.CartItem;
import com.ecomdemo.cart.CartService;
import com.ecomdemo.common.ConflictException;
import com.ecomdemo.order.dto.OrderResponse;
import com.ecomdemo.product.Product;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * One attempt at checkout, inside one transaction.
 *
 * <p>Split out of {@link OrderService} so that the retry can live <em>outside</em> the transaction.
 * Once a transaction has been marked rollback-only there is nothing left to retry inside it, and a
 * retry loop in the same bean would not help anyway: Spring applies {@code @Transactional} with a
 * proxy, and a {@code this.placeOnce()} call from a neighbouring method never reaches it. Two beans
 * make the boundary real rather than a comment asking people to remember it.
 *
 * <p>Package-private on purpose: the world calls {@link OrderService#placeOrder()}, which is the only
 * entry point that retries. The method itself must stay public, because Spring's proxies ignore
 * non-public methods and the transaction would silently never start.
 *
 * <p><strong>Atomicity.</strong> Stock is checked for every line before anything is written, so a
 * five-line order whose fourth line is short changes nothing at all rather than half-completing.
 * The transaction is what turns that from a convention into a guarantee: any exception escaping this
 * method rolls back the order, the stock decrements and the cart clear together.
 */
@Component
@Transactional(readOnly = true)
class OrderPlacement {

    private final OrderRepository orderRepository;
    private final CartService cartService;
    private final OrderAuditService orderAuditService;
    private final Clock clock;

    OrderPlacement(OrderRepository orderRepository,
                   CartService cartService,
                   OrderAuditService orderAuditService,
                   Clock clock) {
        this.orderRepository = orderRepository;
        this.cartService = cartService;
        this.orderAuditService = orderAuditService;
        this.clock = clock;
    }

    /**
     * Turns the current cart into an order, or changes nothing.
     *
     * <p>Every exit records an audit row first. Those writes use {@code REQUIRES_NEW}, so they
     * survive the rollback that the exceptions below trigger.
     */
    @Transactional
    public OrderResponse placeOnce() {
        Cart cart = cartService.requireCart();
        if (cart.isEmpty()) {
            String detail = "Cannot place an order: the cart is empty";
            orderAuditService.record(OrderAudit.Outcome.EMPTY_CART, detail, null);
            throw new ConflictException(detail);
        }

        List<CartItem> cartItems = List.copyOf(cart.getItems());

        // 1. Validate the whole cart first - the prices and stock may have moved since it was filled.
        for (CartItem cartItem : cartItems) {
            Product product = cartItem.getProduct();
            if (!product.hasStockFor(cartItem.getQuantity())) {
                String detail = "Only " + product.getStockQuantity() + " unit(s) of '"
                        + product.getName() + "' in stock, ordered " + cartItem.getQuantity();
                orderAuditService.record(OrderAudit.Outcome.INSUFFICIENT_STOCK, detail, null);
                throw new ConflictException(detail);
            }
        }

        // 2. Build the order, snapshotting name and price, and reduce stock.
        Order order = new Order(clock.instant());
        for (CartItem cartItem : cartItems) {
            Product product = cartItem.getProduct();
            product.reduceStock(cartItem.getQuantity());
            order.addItem(new OrderItem(order, product, cartItem.getQuantity()));
        }
        Order saved = orderRepository.save(order);

        // 3. The cart has become the order, so it is emptied.
        cart.clear();

        // 4. Force the writes out now rather than at commit.
        //
        // The optimistic lock fires on the UPDATE of products, and without this flush that happens
        // after this method returns, while the proxy is committing - too late to audit it here and
        // too late for @Retryable to see anything but a failed commit. Flushing brings the version
        // check inside the method, where the failure can be recorded and re-thrown deliberately.
        try {
            orderRepository.flush();
        } catch (OptimisticLockingFailureException ex) {
            orderAuditService.record(OrderAudit.Outcome.CONCURRENT_MODIFICATION,
                    "Another transaction changed a product first: " + ex.getClass().getSimpleName(), null);
            throw ex;
        }

        orderAuditService.record(OrderAudit.Outcome.PLACED,
                "Order placed with " + saved.getItems().size() + " line(s), total " + saved.getTotalAmount(),
                saved.getId());

        return OrderResponse.from(saved);
    }
}
