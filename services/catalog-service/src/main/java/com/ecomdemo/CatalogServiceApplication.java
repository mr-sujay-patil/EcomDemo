package com.ecomdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The service that owns what the shop sells.
 *
 * <p>The read-heaviest service in the system and the only one with a cache, which is not a
 * coincidence: names, descriptions and prices are written a handful of times a year and read on
 * every page load. Separating them from stock - written on every checkout, never safe to cache - is
 * most of the argument for this particular boundary.
 *
 * <p>It makes no outbound calls at all. It does not know inventory-service exists, has no client for
 * order-service, and publishes no events. Being a leaf is why it can be the first thing to come back
 * after an outage and the last thing to fall over.
 */
@SpringBootApplication
public class CatalogServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogServiceApplication.class, args);
    }
}
