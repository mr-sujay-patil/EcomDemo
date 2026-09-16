package com.ecomdemo.order;

import java.math.BigDecimal;

import com.ecomdemo.order.client.CatalogProduct;

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
 * One purchased line.
 *
 * <p>{@code productName} and {@code unitPrice} are copied from the product at the moment of purchase.
 * This snapshot is the whole point: renaming a product, repricing it, or deleting it must not rewrite
 * history on orders already placed.
 *
 * <h2>Duplication, on purpose</h2>
 *
 * This is the clearest example of deliberate data duplication in the system, and Phase 20 makes it
 * load-bearing rather than merely correct. The name and price live authoritatively in
 * catalog-service and a copy of them lives here, in another database, kept forever and never
 * refreshed.
 *
 * <p>In the monolith that copy was a nicety - the {@code product_id} association was right there, and
 * a developer in a hurry could have rendered the line from live data and only noticed the bug when a
 * price changed. Now there is nothing to be in a hurry with: reading through to catalog-service would
 * be an HTTP call per line, on a historical record, that could fail or return a different answer than
 * the customer was charged. Duplicating the fields at write time is the only sane option, which is
 * the general shape of the argument for duplication across a service boundary.
 *
 * <p>What is NOT here any more is the {@code @ManyToOne Product} that Phase 0 kept "for traceability".
 * {@code product_id} survives as a plain column, with no foreign key and nothing to join to.
 */
@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /** Which product this was. A catalog-service id, unvalidated and unjoinable. */
    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    private int quantity;

    protected OrderItem() {
    }

    /**
     * Snapshots a catalogue product into a purchased line.
     *
     * <p>Takes the DTO that came back over HTTP rather than an entity, because there is no entity to
     * take. The values are copied out immediately and this object never refers to it again.
     */
    public OrderItem(Order order, CatalogProduct product, int quantity) {
        this.order = order;
        this.productId = product.id();
        this.productName = product.name();
        this.unitPrice = product.price();
        this.quantity = quantity;
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
