package com.ecomdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The service that owns how many there are.
 *
 * <p>The smallest service in the system and the one with the sharpest reason to exist. Stock is the
 * only piece of data in this application that several requests genuinely contend for: two shoppers
 * racing for the last unit is the one anomaly READ COMMITTED does not prevent, and Phase 6 added an
 * optimistic lock to handle it. Everything about this service - the version column, the retry, the
 * absence of any cache at all - follows from that one property.
 *
 * <p>Separating it from the catalogue means the data that must be correct-to-the-instant no longer
 * shares a row, a cache policy or a deployment with the data that only has to look right on a page.
 */
@SpringBootApplication
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
