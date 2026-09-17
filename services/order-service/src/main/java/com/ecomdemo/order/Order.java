package com.ecomdemo.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
 * A placed order.
 *
 * <p>{@code "orders"} is quoted-ish by naming the table explicitly because ORDER is a reserved SQL
 * keyword - naming the entity class Order alone would generate invalid DDL on some databases.
 *
 * <p>Unlike the cart, the total is stored. An order is a historical record: it must still show what
 * the customer actually paid even after every product price has changed.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Who placed it: the {@code sub} claim of the token that placed it.
     *
     * <p>A plain column since Phase 20, not an association - the {@code users} table belongs to
     * customer-service. Nothing checks that a customer with this id exists, and nothing can. The id
     * is trustworthy anyway, because it came from a signature this service verified rather than from
     * a request parameter.
     */
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "placed_at", nullable = false)
    private Instant placedAt;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
    }

    public Order(Long customerId, Instant placedAt) {
        this.customerId = customerId;
        this.placedAt = placedAt;
        this.totalAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Adds a line and keeps the stored total in step. Recalculating rather than accumulating means
     * the total can never drift away from the lines it is supposed to summarise.
     */
    public void addItem(OrderItem item) {
        items.add(item);
        this.totalAmount = items.stream()
                .map(OrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    public Long getId() {
        return id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public Instant getPlacedAt() {
        return placedAt;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public List<OrderItem> getItems() {
        return items;
    }
}
