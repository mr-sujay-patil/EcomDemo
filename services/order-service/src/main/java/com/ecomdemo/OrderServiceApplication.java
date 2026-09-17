package com.ecomdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The service that owns carts and orders - and the only one that orchestrates.
 *
 * <p>The other four are leaves: catalog-service, inventory-service and customer-service make no
 * outbound calls at all, and notification-service only consumes. This one calls two services
 * synchronously and publishes to Kafka, which makes it the most interesting service in the system and
 * the most fragile.
 *
 * <p>That asymmetry is not accidental and is worth noticing when reading the code. A checkout here
 * cannot succeed unless catalog-service and inventory-service are both up, so this service's
 * availability is the product of theirs - three services at 99.9% give roughly 99.7% between them,
 * and adding a fourth synchronous dependency would make it worse again. Everything about how this
 * package is written, from the timeouts to the compensating release to the decision to publish the
 * order event asynchronously rather than call notification-service, is an attempt to keep that number
 * from getting smaller.
 */
@SpringBootApplication
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
