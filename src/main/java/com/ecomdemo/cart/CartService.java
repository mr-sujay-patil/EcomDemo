package com.ecomdemo.cart;

import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.cart.dto.UpdateCartItemRequest;
import com.ecomdemo.common.ConflictException;
import com.ecomdemo.common.NotFoundException;
import com.ecomdemo.customer.CustomerService;
import com.ecomdemo.product.Product;
import com.ecomdemo.product.ProductService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the rules of a customer's cart.
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
    private final ProductService productService;
    private final CustomerService customerService;

    public CartService(CartRepository cartRepository,
                       ProductService productService,
                       CustomerService customerService) {
        this.cartRepository = cartRepository;
        this.productService = productService;
        this.customerService = customerService;
    }

    /**
     * Reads the customer's cart without creating one.
     *
     * <p>This method runs in the class-level read-only transaction, so it must not write - and until
     * Phase 8 it never did, because the single shared cart was seeded by a migration and always
     * existed. Now that carts are per customer, the first {@code GET /api/cart} of a new account
     * would have been an INSERT inside a read-only transaction: a 500, and only ever for brand new
     * users. Returning an empty view instead keeps the read a read, and leaves no row behind for
     * somebody who only looked.
     */
    public CartResponse getCart(Long customerId) {
        return cartRepository.findByCustomerIdWithItems(customerId)
                .map(CartResponse::from)
                .orElseGet(CartResponse::empty);
    }

    @Transactional
    public CartResponse addItem(Long customerId, AddCartItemRequest request) {
        Cart cart = requireCart(customerId);
        Product product = productService.requireEntity(request.productId());

        int alreadyInCart = cart.findItemFor(product.getId())
                .map(CartItem::getQuantity)
                .orElse(0);
        requireStock(product, alreadyInCart + request.quantity());

        cart.addOrIncrease(product, request.quantity());
        return CartResponse.from(cart);
    }

    @Transactional
    public CartResponse updateItemQuantity(Long customerId, Long productId, UpdateCartItemRequest request) {
        Cart cart = requireCart(customerId);
        CartItem item = cart.findItemFor(productId)
                .orElseThrow(() -> new NotFoundException("Product " + productId + " is not in the cart"));

        requireStock(item.getProduct(), request.quantity());
        item.changeQuantityTo(request.quantity());
        return CartResponse.from(cart);
    }

    @Transactional
    public CartResponse removeItem(Long customerId, Long productId) {
        Cart cart = requireCart(customerId);
        CartItem item = cart.findItemFor(productId)
                .orElseThrow(() -> new NotFoundException("Product " + productId + " is not in the cart"));

        cart.removeItem(item);
        return CartResponse.from(cart);
    }

    /**
     * Loads the shared cart for other services (the order service) to work with inside their own
     * transaction. Package-private access would be cleaner, but the order feature lives in a
     * different package, so this stays public and returns the entity rather than a DTO on purpose.
     */
    /**
     * The customer's cart, created if this is their first. Only ever called from a method that
     * writes - the cart endpoints that mutate, and the checkout - so the insert is safe.
     */
    public Cart requireCart(Long customerId) {
        return cartRepository.findByCustomerIdWithItems(customerId)
                // A cart is created on first use rather than at registration: an account that never
                // shops should not leave an empty row behind, and the unique constraint on
                // customer_id means a race here fails loudly instead of creating two.
                .orElseGet(() -> cartRepository.save(new Cart(customerService.requireEntity(customerId))));
    }

    private static void requireStock(Product product, int requestedQuantity) {
        if (!product.hasStockFor(requestedQuantity)) {
            throw new ConflictException("Only " + product.getStockQuantity() + " unit(s) of '"
                    + product.getName() + "' in stock, requested " + requestedQuantity);
        }
    }
}
