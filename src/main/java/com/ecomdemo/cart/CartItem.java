package com.ecomdemo.cart;

import java.math.BigDecimal;

import com.ecomdemo.product.Product;

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
 * One line in the cart: a product and how many of it.
 *
 * <p>A cart item holds no price of its own - it reads the live price off the product. That is the
 * correct behaviour for a cart (the customer should see today's price) and the opposite of an
 * {@link com.ecomdemo.order.OrderItem}, which snapshots the price at the moment of purchase.
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
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private int quantity;

    protected CartItem() {
    }

    CartItem(Cart cart, Product product, int quantity) {
        this.cart = cart;
        this.product = product;
        this.quantity = quantity;
    }

    public Long getId() {
        return id;
    }

    public Cart getCart() {
        return cart;
    }

    public Product getProduct() {
        return product;
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

    /** Live price x quantity. */
    public BigDecimal lineTotal() {
        return product.getPrice().multiply(BigDecimal.valueOf(quantity));
    }
}
