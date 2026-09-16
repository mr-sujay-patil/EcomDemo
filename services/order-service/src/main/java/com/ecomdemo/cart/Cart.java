package com.ecomdemo.cart;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/**
 * One customer's cart. Exactly one per user, enforced by a unique constraint on {@code customer_id}
 * rather than trusted to whatever code creates carts.
 *
 * <h2>What Phase 20 took away</h2>
 *
 * The {@code @ManyToOne Customer} association, for the same reason {@link CartItem} lost its
 * {@code Product}: the {@code users} table belongs to customer-service. {@code customerId} is now a
 * plain column holding the {@code sub} claim from a verified token, and nothing checks that a
 * customer with that id exists.
 *
 * <p>That sounds alarming and is mostly fine, because of where the number comes from. It is not a
 * client-supplied parameter; it is a claim customer-service signed and this service verified. A
 * forged id would need the private key. What is genuinely lost is referential integrity over time -
 * delete a customer and their carts become orphans no constraint will complain about - which is the
 * kind of cleanup a real system does with an event rather than a cascade.
 *
 * <p>{@code total()} also went. A cart could price itself while it could reach product prices
 * through an association; it cannot now, so {@link CartService} computes the total from the prices it
 * fetched from catalog-service for the whole cart at once.
 *
 * <p>The {@code items} association is unchanged: both ends live in this database, so it is still an
 * ordinary one-to-many. {@code cascade = ALL} means saving or deleting the cart saves or deletes its
 * items too; {@code orphanRemoval = true} means an item removed from this list is deleted rather than
 * left with a null parent.
 */
@Entity
@Table(name = "carts")
public class Cart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The owner: the {@code sub} claim of the token that created this cart. */
    @Column(name = "customer_id", nullable = false, unique = true)
    private Long customerId;

    /**
     * LAZY is the default for @OneToMany and is kept deliberately: loading a cart should not drag
     * in every item unless the code actually asks for them. The service always accesses this list
     * inside a transaction, so the session is still open when Hibernate initialises it.
     */
    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<CartItem> items = new ArrayList<>();

    protected Cart() {
    }

    public Cart(Long customerId) {
        this.customerId = customerId;
    }

    public Long getId() {
        return id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public List<CartItem> getItems() {
        return items;
    }

    public Optional<CartItem> findItemFor(Long productId) {
        return items.stream()
                .filter(item -> item.getProductId().equals(productId))
                .findFirst();
    }

    /**
     * Adds a product, or increases the quantity if it is already in the cart. Both sides of the
     * association are maintained here so the in-memory object graph and the database agree.
     */
    public CartItem addOrIncrease(Long productId, int quantity) {
        return findItemFor(productId)
                .map(existing -> {
                    existing.increaseBy(quantity);
                    return existing;
                })
                .orElseGet(() -> {
                    CartItem item = new CartItem(this, productId, quantity);
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

    /** The product ids this cart refers to, for fetching their prices in one call. */
    public List<Long> productIds() {
        return items.stream().map(CartItem::getProductId).distinct().toList();
    }
}
