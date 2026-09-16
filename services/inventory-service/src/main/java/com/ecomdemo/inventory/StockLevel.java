package com.ecomdemo.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * How many of one product are available.
 *
 * <h2>The primary key is the product id, and there is no foreign key</h2>
 *
 * {@code product_id} identifies a row in catalog-service's {@code products} table, in a different
 * database. PostgreSQL cannot express a foreign key across databases, so nothing prevents a stock row
 * for a product that does not exist, or a product with no stock row at all.
 *
 * <p>That is the database-per-service trade stated as plainly as it ever gets. What used to be a
 * constraint the engine checked on every insert is now an agreement between two services, and the
 * failure modes it used to make impossible have to be handled instead: {@link StockService} reports a
 * missing row as zero available rather than as an error, which is the answer that keeps a
 * half-created product from taking the shop down.
 *
 * <p>Using the product id as the primary key rather than a generated one is deliberate. It makes
 * "one stock row per product" structural, so a duplicate is rejected by the database rather than by
 * whichever code path happened to check - and it means order-service can address a row by the only
 * identifier it has.
 */
@Entity
@Table(name = "stock_levels")
public class StockLevel {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    /**
     * The optimistic lock, and the reason this service exists as its own deployable.
     *
     * <p>Hibernate increments it on every update and adds {@code AND version = ?} to the WHERE
     * clause, so an update built on a stale read matches zero rows and raises
     * {@code OptimisticLockingFailureException} instead of silently overwriting whoever got there
     * first. Without it, two concurrent orders for the last unit both read {@code quantity = 1} and
     * both write {@code 0}: a lost update, and one unit sold twice.
     *
     * <p>Optimistic rather than pessimistic because collisions are rare and reads are not: taking a
     * row lock on every stock check would serialise the whole shop to protect against something that
     * happens on the last unit of a popular product. The loser is told to redo the work, which
     * {@link StockService} does automatically a bounded number of times.
     */
    @Version
    @Column(nullable = false)
    private long version;

    /** JPA requires a no-arg constructor to instantiate the entity through reflection. */
    protected StockLevel() {
    }

    public StockLevel(Long productId, int quantity) {
        this.productId = productId;
        this.quantity = quantity;
    }

    /**
     * Takes units out of stock, refusing to go negative.
     *
     * <p>Keeping the rule on the entity means no caller can oversell by writing the field directly -
     * the same reasoning that put {@code reduceStock} on {@code Product} in Phase 0. It moved here
     * with the field it protects.
     */
    public void reduce(int amount) {
        if (amount > quantity) {
            throw new IllegalStateException(
                    "Cannot reduce stock of product " + productId + " by " + amount
                            + "; only " + quantity + " available");
        }
        this.quantity -= amount;
    }

    /** Puts units back: an administrator restocking, or a failed checkout being compensated. */
    public void increase(int amount) {
        this.quantity += amount;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public boolean hasAtLeast(int amount) {
        return quantity >= amount;
    }

    public Long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }
}
