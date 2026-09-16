package com.ecomdemo.cart;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.ecomdemo.product.Product;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/**
 * The one shared cart of this phase - no users yet, so a single row with a fixed id.
 *
 * <p>The {@code items} association is the "one" side of a one-to-many. {@code cascade = ALL} means
 * saving or deleting the cart saves or deletes its items too; {@code orphanRemoval = true} means an
 * item removed from this list is deleted from the database rather than left with a null parent.
 */
@Entity
@Table(name = "carts")
public class Cart {

    /** There is exactly one cart in this phase, seeded by data.sql. */
    public static final Long SHARED_CART_ID = 1L;

    @Id
    private Long id;

    /**
     * LAZY is the default for @OneToMany and is kept deliberately: loading a cart should not drag
     * in every item unless the code actually asks for them. The service always accesses this list
     * inside a transaction, so the session is still open when Hibernate initialises it.
     */
    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<CartItem> items = new ArrayList<>();

    protected Cart() {
    }

    public Cart(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public List<CartItem> getItems() {
        return items;
    }

    public Optional<CartItem> findItemFor(Long productId) {
        return items.stream()
                .filter(item -> item.getProduct().getId().equals(productId))
                .findFirst();
    }

    /**
     * Adds a product, or increases the quantity if it is already in the cart. Both sides of the
     * association are maintained here so the in-memory object graph and the database agree.
     */
    public CartItem addOrIncrease(Product product, int quantity) {
        return findItemFor(product.getId())
                .map(existing -> {
                    existing.increaseBy(quantity);
                    return existing;
                })
                .orElseGet(() -> {
                    CartItem item = new CartItem(this, product, quantity);
                    items.add(item);
                    return item;
                });
    }

    public void removeItem(CartItem item) {
        items.remove(item);       // orphanRemoval turns this into a DELETE at flush time
        item.detachFromCart();
    }

    public void clear() {
        items.forEach(CartItem::detachFromCart);
        items.clear();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /**
     * The total is derived from the current product prices every time it is asked for, never stored
     * and never supplied by the client.
     */
    public BigDecimal total() {
        return items.stream()
                .map(CartItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
