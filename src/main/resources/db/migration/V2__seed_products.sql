-- Replaces data.sql, which Spring ran on every start.
--
-- As a migration this runs exactly once per database and is recorded in flyway_schema_history, which
-- is what makes it safe against a database that survives a restart. Phase 4 had to disable data.sql
-- in dev for precisely that reason: re-running it added another ten products on every boot.

INSERT INTO products (name, description, price, stock_quantity) VALUES
    ('Mechanical Keyboard',   'Hot-swappable 75% keyboard with tactile brown switches', 129.99, 40),
    ('Wireless Mouse',        'Ergonomic 6-button mouse, 70-hour battery',               49.50, 120),
    ('27" 4K Monitor',        'IPS panel, 60Hz, USB-C power delivery',                  399.00, 15),
    ('USB-C Hub',             '7-in-1 hub with HDMI, Ethernet and SD card reader',       59.95, 85),
    ('Noise-Cancelling Headphones', 'Over-ear, 30-hour battery, multipoint pairing',    249.00, 30),
    ('Laptop Stand',          'Adjustable aluminium stand, folds flat',                  39.00, 200),
    ('1080p Webcam',          'Auto-focus webcam with a physical privacy shutter',       79.99, 60),
    ('Desk Microphone',       'Cardioid USB condenser microphone with a boom arm',      119.00, 25),
    ('1TB Portable SSD',      'USB 3.2 Gen 2, up to 1050 MB/s read',                    149.99, 50),
    ('Cable Management Kit',  'Sleeves, clips and velcro ties for a tidy desk',          24.99, 300);

-- The single shared cart. CartService.requireCart() recreates this row if it is missing, so the
-- application survives without it - but seeding it here keeps the first request from doing a write.
INSERT INTO carts (id) VALUES (1);
