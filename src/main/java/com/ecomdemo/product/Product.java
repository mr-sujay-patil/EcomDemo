package com.ecomdemo.product;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A catalogue item. This is a persistence concern only: it is never returned from a controller,
 * only mapped into a DTO first.
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

    @Column(name = "stock_quantity", nullable = false)
    private int stockQuantity;

    /**
     * Added by V3__add_product_category.sql. Nullable on purpose: the products seeded by V2 predate
     * the column, and a nullable column is what lets the migration ship before the code that fills
     * it. Left as a plain String rather than an enum while there is no fixed set of categories to
     * enforce - an enum would turn every new category into a code change and a redeploy.
     */
    @Column(length = 100)
    private String category;

    /** JPA requires a no-arg constructor to instantiate the entity through reflection. */
    protected Product() {
    }

    public Product(String name, String description, BigDecimal price, int stockQuantity) {
        this.name = name;
        this.description = description;
        this.price = price;
        this.stockQuantity = stockQuantity;
    }

    /**
     * Reduces stock, refusing to go negative. Keeping this rule on the entity means no caller can
     * accidentally oversell by writing the field directly.
     */
    public void reduceStock(int quantity) {
        if (quantity > stockQuantity) {
            throw new IllegalStateException(
                    "Cannot reduce stock of '" + name + "' by " + quantity + "; only " + stockQuantity + " available");
        }
        this.stockQuantity -= quantity;
    }

    public boolean hasStockFor(int quantity) {
        return stockQuantity >= quantity;
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

    public int getStockQuantity() {
        return stockQuantity;
    }

    public void setStockQuantity(int stockQuantity) {
        this.stockQuantity = stockQuantity;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }
}
