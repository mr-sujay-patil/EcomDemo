package com.ecomdemo.cart;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.cart.dto.UpdateCartItemRequest;
import com.ecomdemo.order.client.CatalogClient;
import com.ecomdemo.order.client.CatalogProduct;
import com.ecomdemo.shared.NotFoundException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;

/**
 * Owns the rules of a customer's cart.
 *
 * <h2>What changed in Phase 20</h2>
 *
 * Pricing. A cart must show today's price - that was true before and has not changed - but the prices
 * are in catalog-service now, so rendering a cart means an HTTP call. Three consequences worth being
 * explicit about:
 *
 * <ul>
 *   <li><strong>One call, not one per line.</strong> {@link #priceCart} fetches the catalogue once and
 *       indexes it. The obvious implementation - ask for each line's product as the line is rendered -
 *       is an N+1 that was nearly free inside one process and is not at all across a network.
 *   <li><strong>Stock is no longer checked when adding to the cart.</strong> It used to be, because
 *       the quantity was one field access away. Doing it now would mean a second network call on every
 *       add, to answer a question whose answer is stale the moment it is given - the shopper may check
 *       out ten minutes later. Stock is checked once, at checkout, against the row itself. The visible
 *       difference is that a shopper can now put more in their cart than exists and only find out at
 *       the end, which is how most real shops behave and is an honest consequence rather than a
 *       regression.
 *   <li><strong>A cart can contain a product that no longer exists.</strong> Nothing stops
 *       catalog-service deleting one. {@link #priceCart} leaves such a line visible with a zero price
 *       rather than failing the whole cart, and checkout refuses it.
 * </ul>
 *
 * <p>Every method that reads the cart for rendering does so inside a transaction and maps to a
 * {@link CartResponse} before returning, because {@code open-in-view} is disabled - a lazy
 * association touched after the transaction closes would fail loudly rather than silently issuing
 * another query.
 */
@Service
@Transactional(readOnly = true)
public class CartService {

    private final CartRepository cartRepository;
    private final CatalogClient catalogClient;

    public CartService(CartRepository cartRepository, CatalogClient catalogClient) {
        this.cartRepository = cartRepository;
        this.catalogClient = catalogClient;
    }

    /**
     * Reads the customer's cart without creating one.
     *
     * <p>This method runs in the class-level read-only transaction, so it must not write. Returning an
     * empty view for a customer who has never added anything keeps the read a read, and leaves no row
     * behind for somebody who only looked.
     */
    public CartResponse getCart(Long customerId) {
        return cartRepository.findByCustomerIdWithItems(customerId)
                .map(this::priceCart)
                .orElseGet(CartResponse::empty);
    }

    @Transactional
    public CartResponse addItem(Long customerId, AddCartItemRequest request) {
        Cart cart = requireCart(customerId);

        // The product must at least exist. This is the one catalogue call on the write path, and it
        // is worth it: without it a typo in a product id becomes a cart line that can never be
        // checked out, discovered minutes later.
        requireProduct(request.productId());

        cart.addOrIncrease(request.productId(), request.quantity());
        return priceCart(cart);
    }

    @Transactional
    public CartResponse updateItemQuantity(Long customerId, Long productId, UpdateCartItemRequest request) {
        Cart cart = requireCart(customerId);
        CartItem item = cart.findItemFor(productId)
                .orElseThrow(() -> new NotFoundException("Product " + productId + " is not in the cart"));

        item.changeQuantityTo(request.quantity());
        return priceCart(cart);
    }

    @Transactional
    public CartResponse removeItem(Long customerId, Long productId) {
        Cart cart = requireCart(customerId);
        CartItem item = cart.findItemFor(productId)
                .orElseThrow(() -> new NotFoundException("Product " + productId + " is not in the cart"));

        cart.removeItem(item);
        return priceCart(cart);
    }

    /**
     * The customer's cart if they have one, without creating it.
     *
     * <p>This is what checkout reads. {@link #requireCart} would have done - it did in Phase 6 - but
     * only because checkout ran inside a read-write transaction that the insert could join. It does
     * not any more: {@code OrderPlacement} opens no transaction, because it makes two HTTP calls and
     * holding a connection across them would be a good way to exhaust the pool. So {@code requireCart}
     * would reach {@code save()} inside this class's read-only transaction, where PostgreSQL rejects
     * the INSERT outright.
     *
     * <p>Worth separating on its own merits, independently of that: a checkout should never create a
     * cart as a side effect of failing. Somebody who posts to {@code /api/orders} having never added
     * anything should get a 409 and leave no row behind, exactly as a GET does.
     */
    public Optional<Cart> findCart(Long customerId) {
        return cartRepository.findByCustomerIdWithItems(customerId);
    }

    /**
     * The customer's cart, created if this is their first. Only ever called from a method that
     * writes - the cart endpoints that mutate, and the checkout - so the insert is safe.
     *
     * <p>Still public and still returning the entity, for the same reason as in Phase 8: checkout
     * lives in another package and needs the managed entity inside its own transaction. The
     * difference now is that "another package" is as far as it goes - both are in this service, this
     * database and this transaction. The seam that used to exist between cart and product is a
     * network call instead, and could not have been an entity hand-off at any price.
     */
    public Cart requireCart(Long customerId) {
        return cartRepository.findByCustomerIdWithItems(customerId)
                // A cart is created on first use rather than at registration: an account that never
                // shops should not leave an empty row behind, and the unique constraint on
                // customer_id means a race here fails loudly instead of creating two.
                .orElseGet(() -> cartRepository.save(new Cart(customerId)));
    }

    /**
     * Renders a cart at today's prices, fetching the catalogue once.
     *
     * <p>A line whose product has disappeared from the catalogue is kept, priced at zero, rather than
     * being dropped or throwing. Dropping it silently would lose the shopper's intent; throwing would
     * make one deleted product break the cart page for everybody holding it. Checkout is where it
     * becomes an error, because that is where it actually matters.
     */
    private CartResponse priceCart(Cart cart) {
        return CartResponse.from(cart, currentPrices(cart.productIds()));
    }

    /**
     * Today's prices for a set of products, indexed by id.
     *
     * <p>Returns an empty map if catalog-service cannot be reached, which renders every line at zero
     * rather than failing the request. That is a judgement call and the opposite one is defensible:
     * showing a cart with wrong totals may be worse than showing an error. It is chosen here because
     * a cart is a read, the numbers are recomputed on the next load, and nothing is bought on the
     * strength of them - checkout prices independently and would fail loudly.
     */
    private Map<Long, CatalogProduct> currentPrices(List<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        try {
            return catalogClient.findAll().stream()
                    .filter(product -> productIds.contains(product.id()))
                    .collect(Collectors.toMap(CatalogProduct::id, Function.identity()));
        } catch (RestClientResponseException | org.springframework.web.client.ResourceAccessException ex) {
            return Map.of();
        }
    }

    private CatalogProduct requireProduct(Long productId) {
        try {
            return catalogClient.findById(productId);
        } catch (RestClientResponseException ex) {
            // catalog-service's own 404, re-raised as this service's NotFoundException so the shopper
            // sees the shared {status, message} shape rather than a leaked upstream body. Translating
            // at the boundary is what keeps one service's error format from becoming another's API.
            if (ex.getStatusCode().value() == 404) {
                throw NotFoundException.of("Product", productId);
            }
            throw ex;
        }
    }
}
