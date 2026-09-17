package com.ecomdemo.order;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.ecomdemo.cart.Cart;
import com.ecomdemo.cart.CartItem;
import com.ecomdemo.cart.CartService;
import com.ecomdemo.order.client.CatalogClient;
import com.ecomdemo.order.client.CatalogProduct;
import com.ecomdemo.order.client.InventoryClient;
import com.ecomdemo.order.client.ReservationRequest;
import com.ecomdemo.order.dto.OrderResponse;
import com.ecomdemo.shared.ConflictException;
import com.ecomdemo.shared.ServiceUnavailableException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * One attempt at checkout - now spanning three services.
 *
 * <h2>What checkout used to be, and what it is now</h2>
 *
 * In Phase 6 this method was a single database transaction: check stock for every line, reduce it,
 * save the order, clear the cart. Any exception rolled back the lot, and a five-line order whose
 * fourth line was short changed nothing at all.
 *
 * <p>The steps are the same. Three of them are now in other processes:
 *
 * <ol>
 *   <li>read the cart - this database;
 *   <li>price the lines - <strong>catalog-service, over HTTP</strong>;
 *   <li>reserve the stock - <strong>inventory-service, over HTTP</strong>, atomic within its database;
 *   <li>save the order and clear the cart - this database, one transaction;
 *   <li>publish {@code orders.placed} - Kafka, after the commit (see {@link OrderService}).
 * </ol>
 *
 * <h2>What Phase 22 changed here</h2>
 *
 * Two things, both about how failure is <em>reported</em> rather than about the flow.
 *
 * <p>A dependency being unreachable is now a <strong>503, not a 409</strong>. The distinction is not
 * cosmetic: 409 says "your request conflicts with our state", which sends a shopper to rebuild a
 * cart that was never the problem, and it is a status nobody alerts on - so an outage reported as a
 * conflict is an outage nobody sees.
 *
 * <p>And the calls themselves are guarded. {@code catalogClient} is a {@code ResilientCatalogClient}
 * (circuit breaker + retry) and {@code inventoryClient} a {@code ResilientInventoryClient}
 * (bulkhead), injected here by interface exactly as before - this class did not need to change to
 * receive them, which is the point of decorating at the bean rather than at the call site.
 *
 * <h2>The part that is not solved, stated plainly</h2>
 *
 * Steps 3 and 4 touch different databases. {@code @Transactional} covers step 4 and can never cover
 * step 3: a transaction is a property of one connection to one database, and there is no connection
 * here that reaches both. So if the order fails to save <em>after</em> the reservation succeeded,
 * stock has been taken for an order that does not exist.
 *
 * <p>What this class does about it is call {@link InventoryClient#release} in a catch block. That is
 * a <em>compensation</em>, not a rollback, and the difference is not pedantic:
 *
 * <ul>
 *   <li>A rollback is guaranteed by the database. This is an HTTP call that can itself fail, and if
 *       it does, the stock stays reserved and nothing will ever retry it.
 *   <li>A rollback is invisible. This is a separate observable event - inventory-service really did
 *       reduce the number, and really did put it back, and anything watching saw both.
 *   <li>If this process is killed between step 3 and the catch block, no compensation happens at all.
 *       There is nothing on disk that remembers a reservation was owed.
 * </ul>
 *
 * <p>Making this reliable needs the reservation to be a durable record with a state machine and a
 * timeout, so an unconfirmed one expires on its own rather than depending on the process that created
 * it still being alive. That is the saga pattern, and it is <strong>Phase 24</strong>. It is not
 * implemented here, and the gap is deliberate and visible rather than papered over with a catch block
 * that looks like it works.
 *
 * <h2>Why the order is this order</h2>
 *
 * Reserve before saving, not after. Both orderings can fail; this one fails in the direction that
 * does not oversell. Saving first and reserving second would mean an order that exists for stock
 * nobody has - a customer who has been told they bought something, which is much worse to unwind than
 * stock briefly held for nobody.
 */
/*
 * No class-level @Transactional, unlike every other orchestrating class in this project.
 *
 * It had `@Transactional(readOnly = true)` in Phase 6, which was right when every step was a local
 * query. It is wrong now: a class-level annotation applies to every method, so placeOnce would hold a
 * database transaction open across two HTTP calls - a pooled connection idle for the duration of
 * somebody else's latency, and the fastest way to turn a slow neighbour into an exhausted pool here.
 *
 * The reads this class still does run in the transactions CartService opens for them, and the write
 * runs in the one OrderWriter opens. This class coordinates and holds nothing.
 */
@Component
class OrderPlacement {

    private static final Logger log = LoggerFactory.getLogger(OrderPlacement.class);

    private final CartService cartService;
    private final CatalogClient catalogClient;
    private final InventoryClient inventoryClient;
    private final OrderWriter orderWriter;
    private final OrderAuditService orderAuditService;

    OrderPlacement(CartService cartService,
                   CatalogClient catalogClient,
                   InventoryClient inventoryClient,
                   OrderWriter orderWriter,
                   OrderAuditService orderAuditService) {
        this.cartService = cartService;
        this.catalogClient = catalogClient;
        this.inventoryClient = inventoryClient;
        this.orderWriter = orderWriter;
        this.orderAuditService = orderAuditService;
    }

    /**
     * Turns the current cart into an order.
     *
     * <p>Every exit records an audit row first. Those writes use {@code REQUIRES_NEW}, so they survive
     * the rollback that the exceptions below trigger - which matters more than it did, because the
     * audit trail is now the only local record that a reservation was ever attempted.
     *
     * <p><strong>This method is deliberately not {@code @Transactional} as a whole.</strong> It calls
     * two remote services, and holding a database transaction open across a network call is one of
     * the reliable ways to run out of connections: the pool is held for the duration of somebody
     * else's latency. The transaction is opened at the last moment, by {@link OrderWriter} - a
     * separate bean, because a {@code this.saveOrder(...)} call would bypass the proxy and run with
     * no transaction at all, silently.
     */
    public OrderResponse placeOnce(Long customerId) {
        Cart cart = requireNonEmptyCart(customerId);
        List<CartItem> cartItems = List.copyOf(cart.getItems());

        // 1. Price the lines from the catalogue. One call for the whole cart.
        Map<Long, CatalogProduct> products = priceLines(cartItems);

        // 2. Reserve the stock. Atomic inside inventory-service: all lines or none.
        ReservationRequest reservation = reservationFor(customerId, cartItems);
        reserve(reservation);

        // 3. Save the order and clear the cart, in one local transaction.
        //
        //    Everything from here is the part that can leave the system inconsistent. If this throws,
        //    the stock taken in step 2 has to be given back by hand, because no transaction covers
        //    both.
        try {
            return orderWriter.saveOrder(
                    customerId,
                    OrderWriter.productIdsOf(cartItems),
                    OrderWriter.quantitiesOf(cartItems),
                    products);
        } catch (RuntimeException orderFailed) {
            compensate(reservation, orderFailed);
            throw orderFailed;
        }
    }

    /**
     * The cart, or a 409.
     *
     * <p>Reads without creating. A customer who has never added anything has no cart row, and
     * checkout must not leave one behind for somebody whose order failed - the same reasoning that
     * makes {@code GET /api/cart} a pure read. It is also load-bearing here: this method runs outside
     * any transaction, so an insert would reach CartService's read-only one and be rejected by
     * PostgreSQL.
     */
    private Cart requireNonEmptyCart(Long customerId) {
        Cart cart = cartService.findCart(customerId).orElse(null);
        if (cart == null || cart.isEmpty()) {
            String detail = "Cannot place an order: the cart is empty";
            orderAuditService.recordAttempt(OrderAudit.Outcome.EMPTY_CART, detail, null);
            throw new ConflictException(detail);
        }
        return cart;
    }

    /**
     * Today's prices for everything in the cart.
     *
     * <p>A line whose product has vanished from the catalogue is refused here rather than being
     * charged at zero. The cart page tolerates it; buying it must not.
     */
    private Map<Long, CatalogProduct> priceLines(List<CartItem> cartItems) {
        List<Long> productIds = cartItems.stream().map(CartItem::getProductId).distinct().toList();

        Map<Long, CatalogProduct> products;
        try {
            products = catalogClient.findAll().stream()
                    .filter(product -> productIds.contains(product.id()))
                    .collect(Collectors.toMap(CatalogProduct::id, Function.identity()));
        } catch (ServiceUnavailableException | RestClientException ex) {
            // No fallback and no cached price. Charging somebody from a stale number is worse than
            // telling them to try again, so this is one of the places where a dependency being down
            // genuinely means this service cannot do its job.
            //
            // 503, not the 409 this threw until Phase 22. A 409 told the shopper their cart was the
            // problem; they would rebuild it and hit the same wall. It also lied to the dashboard -
            // a 409 is a normal business outcome nobody alerts on, so reporting an outage as one
            // made the outage invisible.
            String detail = "Cannot price the cart: catalog-service is unavailable";
            orderAuditService.recordAttempt(OrderAudit.Outcome.INSUFFICIENT_STOCK, detail, null);
            throw new ServiceUnavailableException(
                    "The product catalogue is temporarily unavailable. Please try again shortly.", ex);
        }

        for (Long productId : productIds) {
            if (!products.containsKey(productId)) {
                String detail = "Product " + productId + " is no longer available";
                orderAuditService.recordAttempt(OrderAudit.Outcome.INSUFFICIENT_STOCK, detail, null);
                throw new ConflictException(detail);
            }
        }
        return products;
    }

    private static ReservationRequest reservationFor(Long customerId, List<CartItem> cartItems) {
        return new ReservationRequest(
                "customer-" + customerId,
                cartItems.stream()
                        .map(item -> new ReservationRequest.Line(item.getProductId(), item.getQuantity()))
                        .toList());
    }

    /**
     * Takes the stock, translating inventory-service's answers into this service's vocabulary.
     *
     * <p>A 409 from there means what a failed stock check meant in Phase 0, and becomes the same 409
     * here. Anything else - a connection refused, a timeout, a 500 - is not the shopper's fault and
     * must not be reported as if their cart were at fault.
     */
    private void reserve(ReservationRequest reservation) {
        try {
            inventoryClient.reserve(reservation);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 409) {
                String detail = "Not enough stock: " + ex.getResponseBodyAsString();
                orderAuditService.recordAttempt(OrderAudit.Outcome.INSUFFICIENT_STOCK, detail, null);
                throw new ConflictException("One or more items are no longer available in the quantity requested");
            }
            throw ex;
        } catch (ServiceUnavailableException ex) {
            // Already the right shape - the bulkhead rejected the call, or a fallback turned a
            // transport failure into a 503. Recorded and re-thrown untouched.
            orderAuditService.recordAttempt(OrderAudit.Outcome.INSUFFICIENT_STOCK,
                    "Cannot reserve stock: " + ex.getMessage(), null);
            throw ex;
        } catch (RestClientException ex) {
            String detail = "Cannot reserve stock: inventory-service is unavailable";
            orderAuditService.recordAttempt(OrderAudit.Outcome.INSUFFICIENT_STOCK, detail, null);
            throw new ServiceUnavailableException(
                    "Checkout is temporarily unavailable. Please try again shortly.", ex);
        }
    }

    /**
     * Gives back stock that was reserved for an order that never got saved.
     *
     * <p>Best effort, and the failure of this call is swallowed on purpose: the caller is already
     * throwing, and replacing its exception with this one would hide why the checkout actually
     * failed. What is left is a log line at ERROR and a discrepancy somebody has to notice.
     *
     * <p>That is the honest state of this phase. A durable, retried, expiring compensation is Phase 24.
     */
    private void compensate(ReservationRequest reservation, RuntimeException cause) {
        log.error("Order failed after stock was reserved; releasing {} line(s). Cause: {}",
                reservation.lines().size(), cause.toString());
        try {
            inventoryClient.release(reservation);
        } catch (RestClientException releaseFailed) {
            log.error("COMPENSATION FAILED - stock is reserved for an order that does not exist. "
                            + "Reservation: {}. This needs manual correction; a durable saga (Phase 24) "
                            + "is what removes the need for it.",
                    reservation, releaseFailed);
        }
    }
}
