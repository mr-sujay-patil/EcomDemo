package com.ecomdemo.order;

import java.time.Clock;
import java.util.List;

import com.ecomdemo.cart.Cart;
import com.ecomdemo.cart.CartItem;
import com.ecomdemo.cart.CartService;
import com.ecomdemo.common.ConflictException;
import com.ecomdemo.common.NotFoundException;
import com.ecomdemo.order.dto.OrderResponse;
import com.ecomdemo.product.Product;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The checkout use case - the one place in this application where several aggregates change
 * together, which is exactly why it needs a transaction.
 */
@Service
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final CartService cartService;
    private final Clock clock;

    public OrderService(OrderRepository orderRepository, CartService cartService, Clock clock) {
        this.orderRepository = orderRepository;
        this.cartService = cartService;
        this.clock = clock;
    }

    /**
     * Turns the current cart into an order.
     *
     * <p>Stock is checked for every line <em>before</em> anything is written. Checking up front
     * means a five-line order does not half-complete when line four runs out; combined with
     * {@code @Transactional}, either the whole order is placed or nothing changes at all.
     *
     * <p>The stock decrement and the cart clear need no explicit save() - both entities are managed
     * inside this transaction, so Hibernate's dirty checking flushes them on commit.
     */
    @Transactional
    public OrderResponse placeOrder() {
        Cart cart = cartService.requireCart();
        if (cart.isEmpty()) {
            throw new ConflictException("Cannot place an order: the cart is empty");
        }

        List<CartItem> cartItems = List.copyOf(cart.getItems());

        // 1. Validate the whole cart first - the prices and stock may have moved since it was filled.
        for (CartItem cartItem : cartItems) {
            Product product = cartItem.getProduct();
            if (!product.hasStockFor(cartItem.getQuantity())) {
                throw new ConflictException("Only " + product.getStockQuantity() + " unit(s) of '"
                        + product.getName() + "' in stock, ordered " + cartItem.getQuantity());
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

        return OrderResponse.from(saved);
    }

    public List<OrderResponse> findAll() {
        return orderRepository.findAllWithItems().stream()
                .map(OrderResponse::from)
                .toList();
    }

    public OrderResponse findById(Long id) {
        return orderRepository.findByIdWithItems(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> NotFoundException.of("Order", id));
    }
}
