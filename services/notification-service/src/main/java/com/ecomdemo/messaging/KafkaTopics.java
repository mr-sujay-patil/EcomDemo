package com.ecomdemo.messaging;

/**
 * The topics this service subscribes to.
 *
 * <p>A second declaration of names that order-service also declares - deliberately, and it is the
 * clearest small example of what a contract between services is.
 *
 * <p>Putting these constants in shared-kernel would compile and would be a mistake of the same kind
 * as a shared DTO: it would turn "both services agree that the topic is called orders.placed" from an
 * agreement into a compile-time dependency, so renaming the constant would force both to be rebuilt
 * and redeployed together. The agreement is about the string, and the string is what travels.
 *
 * <p>What actually keeps them in step is that changing a topic name is a deployment event either way.
 * A shared constant would not prevent that; it would only make it look like a refactor.
 */
public final class KafkaTopics {

    private KafkaTopics() {
    }

    /** Published by order-service after a checkout commits. */
    public static final String ORDERS_PLACED = "orders.placed";

    /**
     * Created by {@code @RetryableTopic} on {@code OrderPlacedListener}, not declared as beans here.
     * Named so that tests and the README can refer to them without repeating string literals.
     */
    public static final String ORDERS_PLACED_RETRY_0 = "orders.placed-retry-0";
    public static final String ORDERS_PLACED_RETRY_1 = "orders.placed-retry-1";
    public static final String ORDERS_PLACED_DLT = "orders.placed-dlt";
}
