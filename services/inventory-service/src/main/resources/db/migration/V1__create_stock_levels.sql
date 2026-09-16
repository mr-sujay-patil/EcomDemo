-- Stock, extracted from the products table it used to be a column on.
--
-- In the monolith, products.stock_quantity sat next to name, description and price. This phase pulls
-- it into its own service and its own database, and the reason is visible in the two columns below:
-- a version column for optimistic locking, and nothing else. Stock is the only data in this
-- application that concurrent requests genuinely fight over, and it was sharing a row - and a cache
-- policy, and a deployment - with data that is read constantly and written twice a year.
--
-- THERE IS NO FOREIGN KEY TO products, and there cannot be: that table is in catalog-service's
-- database, and PostgreSQL has no cross-database references. What was a constraint the engine
-- checked is now an agreement between two services. StockService answers "no row" as zero available
-- rather than as an error, because a product created a second ago whose stock has not been set yet
-- is an ordinary state, not a broken one.

CREATE TABLE stock_levels (
    -- The product id IS the primary key rather than a generated one. It makes "one stock row per
    -- product" structural - a duplicate is rejected by the database rather than by whichever code
    -- path happened to check - and it lets order-service address a row by the only identifier it
    -- has.
    product_id BIGINT  PRIMARY KEY,
    quantity   INTEGER NOT NULL,

    -- The optimistic lock. Hibernate increments it on every update and writes
    --     UPDATE stock_levels SET quantity = ?, version = 4 WHERE product_id = ? AND version = 3
    -- so an update built on a stale read matches zero rows and raises an exception instead of
    -- silently overwriting whoever got there first.
    --
    -- Without it, two concurrent orders for the last unit both read quantity = 1, both write 0, and
    -- one unit is sold twice. That is a lost update, and it is the one anomaly READ COMMITTED does
    -- not prevent. It is also the entire reason this service is a service.
    version    BIGINT  NOT NULL DEFAULT 0,

    -- Belt and braces alongside StockLevel.reduce(), which refuses to go negative in Java. The
    -- database check is what holds if a future code path forgets, or if somebody runs an UPDATE by
    -- hand at 3am.
    CONSTRAINT ck_stock_levels_not_negative CHECK (quantity >= 0)
);
