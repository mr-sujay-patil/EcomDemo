package com.ecomdemo.messaging;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;

import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

/**
 * This service is a consumer that has to be able to produce.
 *
 * <h2>Why a pure consumer needs a serializer at all</h2>
 *
 * {@code @RetryableTopic} does not retry in place. It <em>republishes</em> the failed record to
 * {@code orders.placed-retry-0}, then {@code -retry-1}, then {@code orders.placed-dlt}, using this
 * application's {@code KafkaTemplate}. So notification-service produces to four topics without ever
 * looking like a producer, and the serializer it uses to do so is easy to forget entirely.
 *
 * <h2>The failure this prevents, which is worth describing because it is so indirect</h2>
 *
 * A record that failed to <em>deserialize</em> never became an object, so what the recoverer forwards
 * is the original {@code byte[]}. With Boot's default value serializer - {@code StringSerializer} -
 * that send fails with {@code Can't convert value of class [B to class StringSerializer}.
 *
 * <p>And then the interesting part. The recoverer failing means the record is never recovered, so the
 * container retries it; the retry fails identically; and the record sits at the head of its partition
 * forever. A partition is an ordered log read by one consumer, so <strong>every subsequent order's
 * confirmation is stuck behind one malformed message</strong> - the exact head-of-line blocking that
 * {@code @RetryableTopic} exists to prevent, reintroduced by a serializer mismatch three layers away.
 *
 * <p>It presents as "the consumer has stopped working" with a single line about a serializer buried in
 * the log, and it is why this class exists rather than a default being relied on. (Found exactly that
 * way: every integration test in this service timed out at once, including the ones that had nothing
 * to do with poison messages.)
 *
 * <h2>The three delegates</h2>
 *
 * Matched in iteration order, which is why this is a {@code LinkedHashMap} and why {@code Object}
 * must come last - it matches everything.
 *
 * <ul>
 *   <li>{@code byte[]} - a payload that could not be parsed, on its way to the DLT. Passed through
 *       untouched, so what lands there is the malformed text somebody needs to read rather than
 *       base64 of it.
 *   <li>{@code String} - what a test publishes when it wants to send the exact bytes a producer in
 *       another service would send.
 *   <li>{@code Object} - a record that deserialized fine and failed while being handled, on its way
 *       to a retry topic. Jackson writes it out again.
 * </ul>
 *
 * <p>{@link DefaultKafkaProducerFactoryCustomizer} rather than a {@code KafkaTemplate} bean: Boot's
 * template is {@code @ConditionalOnMissingBean}, so declaring one makes the auto-configured one
 * silently disappear along with every {@code spring.kafka.producer.*} setting.
 */
@Configuration
public class DeadLetterProducerConfiguration {

    @Bean
    DefaultKafkaProducerFactoryCustomizer serializeByPayloadType() {
        return DeadLetterProducerConfiguration::applyDelegatingSerializer;
    }

    /**
     * The cast is why this is a method rather than a lambda body: the customizer is handed a
     * {@code DefaultKafkaProducerFactory<?, ?>}, and a wildcard capture will not accept a
     * {@code Serializer<Object>}.
     */
    @SuppressWarnings("unchecked")
    private static void applyDelegatingSerializer(DefaultKafkaProducerFactory<?, ?> factory) {
        Map<Class<?>, Serializer<?>> delegates = new LinkedHashMap<>();
        delegates.put(byte[].class, new ByteArraySerializer());
        delegates.put(String.class, new StringSerializer());
        delegates.put(Object.class, new JacksonJsonSerializer<>());

        ((DefaultKafkaProducerFactory<Object, Object>) factory).setValueSerializer(
                // true = match by assignability rather than by exact class. Without it the Object
                // entry is a catch-all that catches nothing.
                new DelegatingByTypeSerializer(delegates, true));
    }
}
