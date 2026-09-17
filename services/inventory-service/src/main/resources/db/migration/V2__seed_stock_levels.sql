-- Opening stock for the ten products catalog-service seeds in its own V2.
--
-- The quantities are the ones that used to sit on each product row. What has changed is that they
-- are in a different database from the names they describe, so these ids are a promise rather than a
-- reference: nothing here checks that product 7 exists, and nothing in catalog-service checks that
-- it has stock.
--
-- Keeping two seed files in step by hand is exactly the kind of chore database-per-service creates,
-- and it is worth feeling it at ten rows to understand why real systems either publish an event when
-- a product is created or accept that stock rows appear on first use.

INSERT INTO stock_levels (product_id, quantity) VALUES
    (1,  40),   -- Mechanical Keyboard
    (2, 120),   -- Wireless Mouse
    (3,  15),   -- 27" 4K Monitor
    (4,  85),   -- USB-C Hub
    (5,  30),   -- Noise-Cancelling Headphones
    (6, 200),   -- Laptop Stand
    (7,  60),   -- 1080p Webcam
    (8,  25),   -- Desk Microphone
    (9,  50),   -- 1TB Portable SSD
    (10, 300);  -- Cable Management Kit
