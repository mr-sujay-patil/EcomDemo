package com.ecomdemo.messaging;

import org.apache.kafka.clients.admin.NewTopic;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topics this application owns, so they exist with the right shape rather than the
 * right-by-accident shape.
 *
 * <p>A broker will happily create a topic on first use ({@code auto.create.topics.enable} defaults
 * to true), and that is the trap this class exists to avoid: an auto-created topic takes the
 * broker's defaults, which means one partition. Partition count cannot be lowered afterwards, and
 * raising it breaks the ordering guarantee described below for every key already in the topic. It is
 * a decision that is cheap now and expensive in six months.
 *
 * <p>Spring applies these through {@code KafkaAdmin} at startup. Declaring a topic that already
 * exists is a no-op, and increasing {@code partitions} here will grow an existing topic - but a
 * decrease is silently ignored rather than applied, which is the broker protecting the data.
 */
@Configuration
public class KafkaTopicConfiguration {

    /**
     * Three partitions.
     *
     * <p>Partitions are the unit of parallelism: each one is an independent append-only log, and
     * one partition is read by at most one consumer in a group. Three therefore means up to three
     * instances of this application can share the notification work; a fourth would idle.
     *
     * <p>They are also the unit of <strong>ordering</strong>. Kafka guarantees order within a
     * partition and offers nothing across them, so "in order" is only ever a statement about
     * records that share a partition. The producer picks the partition by hashing the message key,
     * which is why {@code OrderEventPublisher} keys by order id: every event about one order lands
     * in the same partition and is therefore consumed in the order it was produced, while different
     * orders spread across all three and are processed in parallel.
     *
     * <p>A null key would round-robin instead, and two events about the same order could then be
     * handled out of order by two consumers. With one event type that is harmless; it stops being
     * harmless the moment {@code orders.cancelled} joins the topic.
     */
    static final int PARTITIONS = 3;

    /**
     * One replica, because this stack has one broker - and this number is exactly the amount of
     * broker failure the topic can survive, which here is none.
     *
     * <p>Replication is what makes Kafka durable: with a factor of 3, each partition is stored on
     * three brokers, one leader and two followers, and the cluster elects a new leader when one
     * dies. Asking for more replicas than there are brokers fails topic creation outright, so a
     * single-node learning stack has to say 1 and be honest about what it has given up.
     */
    static final short REPLICAS = 1;

    @Bean
    NewTopic ordersPlacedTopic() {
        return TopicBuilder.name(KafkaTopics.ORDERS_PLACED)
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }
}
