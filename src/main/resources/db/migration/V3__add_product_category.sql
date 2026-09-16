-- A realistic schema change: products gain a category.
--
-- This migration is deliberately backward compatible - the "expand" half of expand/contract:
--
--   * the column is NULLABLE and has no default, so the ten rows V2 inserted stay valid and the
--     statement rewrites no data and takes no long lock.
--   * code that has never heard of `category` keeps working. That matters during a rolling deploy,
--     where the old and new versions of the application run against this same database at the same
--     time for a few minutes.
--
-- Making it NOT NULL would have required a value for every existing row and would break the running
-- old version instantly. That tightening, if it is ever wanted, belongs in a later migration once
-- every row has a value and every instance writes one - the "contract" half, and never on the same
-- deploy as the expand.
ALTER TABLE products ADD COLUMN category VARCHAR(100);

-- Supports the "show me everything in this category" queries a catalogue inevitably grows. The
-- index is created after the column exists and covers the NULLs harmlessly.
CREATE INDEX idx_products_category ON products (category);
