package com.ecomdemo.product;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A catalogue item: what a thing is called, what it costs, what it is.
 *
 * <h2>What left in Phase 20</h2>
 *
 * {@code stockQuantity}, and with it {@code reduceStock} and {@code hasStockFor}. Stock is
 * inventory-service's data now, in its own table in its own database, and this class has no way to
 * read it and no business doing so.
 *
 * <p>That split is not arbitrary tidying. Price and description are written by an administrator a
 * few times a year and read on every page load - which is why this service caches them in Redis.
 * Stock is written by every checkout and must never be served from a cache, because two shoppers
 * given the same remembered figure both pass the "is there enough?" check. Two fields on one row
 * with opposite requirements were being managed by one set of compromises; separating them lets each
 * have what it needs.
 *
 * <p>The cost is equally concrete and shows up immediately: nothing can now join products to stock,
 * so {@code GET /api/products} cannot report availability. Composing the two views is the gateway's
 * job in Phase 21; until then a client asks catalog-service what exists and inventory-service how
 * many there are.
 *
 * <p>This is a persistence concern only: it is never returned from a controller, only mapped into a
 * DTO first.
 */
@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(length = 1000)
    private String description;

    /**
     * Money is stored as an exact decimal, never as double. precision/scale make the database
     * column NUMERIC(12,2), so the rounding rules are enforced by the schema and not just by code.
     */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    /**
     * Added by V3__add_product_category.sql. Nullable on purpose: the products seeded by V2 predate
     * the column, and a nullable column is what lets the migration ship before the code that fills
     * it. Left as a plain String rather than an enum while there is no fixed set of categories to
     * enforce - an enum would turn every new category into a code change and a redeploy.
     */
    @Column(length = 100)
    private String category;

    /**
     * The optimistic lock. Hibernate increments this on every update and adds {@code AND version = ?}
     * to the WHERE clause, so an update built on a stale read matches no rows and fails loudly
     * instead of silently overwriting whoever got there first.
     *
     * <p>No getter is exposed and nothing in the application ever reads it: it belongs to Hibernate,
     * and putting it in {@code ProductResponse} would leak a persistence detail into the API.
     *
     * <p>Stock used to be the field that made this necessary, and stock has gone to inventory-service
     * - where an identical {@code @Version} column now guards the row that actually contends. This
     * one is kept because concurrent catalogue edits are still a lost update waiting to happen: two
     * administrators repricing the same product from the same stale read. It is much rarer, which is
     * exactly the shape optimistic locking suits.
     */
    @Version
    @Column(nullable = false)
    private long version;

    /** JPA requires a no-arg constructor to instantiate the entity through reflection. */
    protected Product() {
    }

    public Product(String name, String description, BigDecimal price) {
        this.name = name;
        this.description = description;
        this.price = price;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }
}
