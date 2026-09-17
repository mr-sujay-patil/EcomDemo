package com.ecomdemo.cart;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One line in the cart: a product id and how many of it.
 *
 * <h2>What Phase 20 took away</h2>
 *
 * The {@code @ManyToOne Product} association. {@code products} is catalog-service's table in
 * catalog-service's database, so there is no entity to associate with and no foreign key to hold it
 * in place. What is left is {@code product_id}: a number this service stores, cannot validate, and
 * cannot join to.
 *
 * <p>{@code lineTotal()} went with it, and that is the more interesting loss. A cart line used to be
 * able to price itself by reading {@code product.getPrice()} - a field access that quietly became a
 * lazy SELECT. It cannot any more, because the price is on the far side of a network call that has
 * to be made once for the whole cart rather than once per line. Pricing therefore moved up into
 * {@link CartService}, where the batch fetch can happen.
 *
 * <p>That is worth naming as a pattern rather than an inconvenience: splitting a service tends to
 * pull behaviour <em>out</em> of entities and into the layer that can see the whole request, because
 * the cheap per-object lookups that made rich entities pleasant are exactly what becomes expensive
 * when they cross a process boundary.
 *
 * <p>Still true, and still the important contrast with {@link com.ecomdemo.order.OrderItem}: a cart
 * line holds no price of its own. The customer must see today's price, so it is fetched live every
 * time. An order line snapshots the price at the moment of purchase and never looks it up again.
 */
@Entity
@Table(name = "cart_items")
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The "many" side owns the foreign key - the cart_items table gets a cart_id column.
     * ManyToOne defaults to EAGER, which would issue an extra query for every item loaded; LAZY is
     * set explicitly to avoid that.
     *
     * <p>This association survived the split because both ends are in this service's database. It is
     * a useful reminder that database-per-service forbids joins <em>across</em> services, not within
     * one - order-service's own schema is as relational as it ever was.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    /** A catalog-service id. Nothing here can check that it refers to anything. */
    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    protected CartItem() {
    }

    CartItem(Cart cart, Long productId, int quantity) {
        this.cart = cart;
        this.productId = productId;
        this.quantity = quantity;
    }

    public Long getId() {
        return id;
    }

    public Cart getCart() {
        return cart;
    }

    public Long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    void increaseBy(int amount) {
        this.quantity += amount;
    }

    void changeQuantityTo(int newQuantity) {
        this.quantity = newQuantity;
    }

    void detachFromCart() {
        this.cart = null;
    }
}
