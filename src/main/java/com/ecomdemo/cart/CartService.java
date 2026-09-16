package com.ecomdemo.cart;

import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.cart.dto.UpdateCartItemRequest;
import com.ecomdemo.common.ConflictException;
import com.ecomdemo.common.NotFoundException;
import com.ecomdemo.product.Product;
import com.ecomdemo.product.ProductService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the rules of the single shared cart.
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

    public CartService(CartRepository cartRepository, ProductService productService) {
        this.cartRepository = cartRepository;
        this.productService = productService;
    }

    public CartResponse getCart() {
        return CartResponse.from(requireCart());
    }

    @Transactional
    public CartResponse addItem(AddCartItemRequest request) {
        Cart cart = requireCart();
        Product product = productService.requireEntity(request.productId());

        int alreadyInCart = cart.findItemFor(product.getId())
                .map(CartItem::getQuantity)
                .orElse(0);
        requireStock(product, alreadyInCart + request.quantity());

        cart.addOrIncrease(product, request.quantity());
        return CartResponse.from(cart);
    }

    @Transactional
    public CartResponse updateItemQuantity(Long productId, UpdateCartItemRequest request) {
        Cart cart = requireCart();
        CartItem item = cart.findItemFor(productId)
                .orElseThrow(() -> new NotFoundException("Product " + productId + " is not in the cart"));

        requireStock(item.getProduct(), request.quantity());
        item.changeQuantityTo(request.quantity());
        return CartResponse.from(cart);
    }

    @Transactional
    public CartResponse removeItem(Long productId) {
        Cart cart = requireCart();
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
    public Cart requireCart() {
        return cartRepository.findByIdWithItems(Cart.SHARED_CART_ID)
                // data.sql seeds the shared cart; recreate it if someone wiped the table by hand.
                .orElseGet(() -> cartRepository.save(new Cart(Cart.SHARED_CART_ID)));
    }

    private static void requireStock(Product product, int requestedQuantity) {
        if (!product.hasStockFor(requestedQuantity)) {
            throw new ConflictException("Only " + product.getStockQuantity() + " unit(s) of '"
                    + product.getName() + "' in stock, requested " + requestedQuantity);
        }
    }
}
