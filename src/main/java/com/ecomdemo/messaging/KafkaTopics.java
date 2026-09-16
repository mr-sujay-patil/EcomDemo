package com.ecomdemo.messaging;

/**
 * The topic names this application produces to and consumes from.
 *
 * <p>Constants rather than literals because a topic name is a contract between two pieces of code
 * that never call each other. A typo in a {@code @KafkaListener} does not fail - it subscribes to a
 * topic nobody writes to, and the consumer sits there healthily receiving nothing.
 *
 * <p>The naming is {@code <aggregate>.<event>}, past tense: the topic carries a record of something
 * that has already happened, not an instruction to do something. That distinction is the whole
 * difference between an event and a command, and it is what lets a second consumer be added later
 * without asking the producer's permission.
 */
public final class KafkaTopics {

    /** One record per order that was successfully placed. Keyed by order id. */
    public static final String ORDERS_PLACED = "orders.placed";

    /**
     * Where {@code @RetryableTopic} parks a record it could not handle.
     *
     * <p>Spring derives these names from the main topic plus a suffix, so they are written out here
     * only for the tests and the documentation to refer to - nothing configures them from these
     * constants.
     */
    public static final String ORDERS_PLACED_RETRY = ORDERS_PLACED + "-retry";

    /** The dead-letter topic: records that exhausted their retries, or that were never valid. */
    public static final String ORDERS_PLACED_DLT = ORDERS_PLACED + "-dlt";

    private KafkaTopics() {
    }
}
