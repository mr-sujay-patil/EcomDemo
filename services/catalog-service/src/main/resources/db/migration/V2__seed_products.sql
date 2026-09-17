-- The ten products the shop starts with.
--
-- As a migration this runs exactly once per database and is recorded in flyway_schema_history, which
-- is what makes it safe against a database that survives a restart. (Phase 4 had to disable data.sql
-- in dev for precisely that reason: re-running it added another ten products on every boot.)
--
-- The quantities that used to be on each row are not missing, they are elsewhere: inventory-service's
-- own V2 seeds stock for product ids 1..10. Two services, two seed files, two databases - and no
-- foreign key between them, so the two files agreeing about which ids exist is a convention that
-- nothing enforces. Getting them out of step would produce a product with no stock row, which
-- inventory-service reports as zero available rather than as an error.
--
-- That is a fair miniature of what database-per-service costs: a constraint the engine used to check
-- becomes a promise two teams have to keep.

INSERT INTO products (name, description, price) VALUES
    ('Mechanical Keyboard',   'Hot-swappable 75% keyboard with tactile brown switches', 129.99),
    ('Wireless Mouse',        'Ergonomic 6-button mouse, 70-hour battery',               49.50),
    ('27" 4K Monitor',        'IPS panel, 60Hz, USB-C power delivery',                  399.00),
    ('USB-C Hub',             '7-in-1 hub with HDMI, Ethernet and SD card reader',       59.95),
    ('Noise-Cancelling Headphones', 'Over-ear, 30-hour battery, multipoint pairing',    249.00),
    ('Laptop Stand',          'Adjustable aluminium stand, folds flat',                  39.00),
    ('1080p Webcam',          'Auto-focus webcam with a physical privacy shutter',       79.99),
    ('Desk Microphone',       'Cardioid USB condenser microphone with a boom arm',      119.00),
    ('1TB Portable SSD',      'USB 3.2 Gen 2, up to 1050 MB/s read',                    149.99),
    ('Cable Management Kit',  'Sleeves, clips and velcro ties for a tidy desk',          24.99);
