package com.ecomdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The service that tells customers their order went through.
 *
 * <p>The only service nothing calls. It has one HTTP endpoint for reading confirmations back, and
 * everything it actually does is driven by a Kafka record - so order-service can place an order
 * whether or not this service exists, is deployed, or has crashed. When it comes back it reads from
 * its last committed offset and catches up.
 *
 * <p>That is the practical difference between the two kinds of communication in this system, and it
 * is worth stating next to the checkout that demonstrates the other one. A checkout cannot complete
 * without catalog-service and inventory-service answering <em>now</em>, because it needs their
 * answers to decide. A confirmation needs nothing from anybody: the event carries everything, so the
 * work can happen late without happening wrongly.
 *
 * <p>The cost of that independence is eventual consistency. Between the commit in order-service and
 * the row written here there is a window - usually milliseconds, occasionally an outage - in which an
 * order exists and its confirmation does not. Nothing here can close that window; the design accepts
 * it in exchange for the two services never being able to take each other down.
 */
@SpringBootApplication
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
