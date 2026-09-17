package com.ecomdemo.messaging;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;

import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

/**
 * Makes the producer choose its serializer from what is actually being sent.
 *
 * <h2>Why</h2>
 *
 * {@code @RetryableTopic} forwards a failed record to the retry and dead-letter topics using the
 * application's {@code KafkaTemplate}. For a record that failed while being <em>handled</em> that is
 * unremarkable: the payload is an {@code OrderPlacedEvent} and Jackson writes it out again.
 *
 * <p>The interesting case is the one a dead-letter topic mostly exists for. A record that failed to
 * <em>deserialize</em> never became an object, so what gets forwarded is the original
 * {@code byte[]} - and asking a JSON serializer to write a byte array produces a JSON string
 * containing base64. The poison message still arrives in the DLT, so nothing looks broken, but what
 * is stored there reads {@code "e25vdCBldmVuIHZhbGlkIGpzb24="} instead of the malformed payload
 * somebody needs to see. A dead-letter topic whose contents must be base64-decoded before they can
 * be read has lost most of its point, and there is no way to discover this except by opening it.
 *
 * <p>{@link DelegatingByTypeSerializer} picks by the payload's runtime type: raw bytes pass through
 * untouched, everything else goes through Jackson exactly as before.
 *
 * <h2>Why a customizer rather than a bean of our own</h2>
 *
 * A serializer belongs to the {@code ProducerFactory} - {@code KafkaTemplate} has no setter for one
 * - and the obvious move is therefore to declare a {@code ProducerFactory} or a
 * {@code KafkaTemplate} bean. Both are traps, and both were tried here first:
 *
 * <ul>
 *   <li>An extra {@code KafkaTemplate} bean does not sit alongside Boot's - Boot's is
 *       {@code @ConditionalOnMissingBean(KafkaTemplate.class)}, so <em>any</em> template bean makes
 *       it back off. The auto-configured one silently disappears and everything starts using the
 *       replacement, whatever it was declared for.
 *   <li>A {@code ProducerFactory} bean fails more loudly but no more obviously: Boot's
 *       {@code kafkaTemplate} asks for a {@code ProducerFactory<Object, Object>}, so a
 *       {@code ProducerFactory<String, Object>} does not qualify and the context will not start.
 * </ul>
 *
 * <p>{@link DefaultKafkaProducerFactoryCustomizer} is the hook Boot provides instead: the
 * auto-configuration builds its factory as usual and hands it here to be adjusted. Every
 * {@code spring.kafka.producer.*} setting still applies, and nothing is replaced.
 */
@Configuration
public class KafkaProducerConfiguration {

    @Bean
    DefaultKafkaProducerFactoryCustomizer serializeByPayloadType() {
        return KafkaProducerConfiguration::applyDelegatingSerializer;
    }

    /**
     * The cast is why this is a method rather than a lambda body: the customizer is handed a
     * {@code DefaultKafkaProducerFactory<?, ?>}, and a wildcard capture will not accept a
     * {@code Serializer<Object>}. Narrowing it in one place keeps the unchecked warning in one
     * place too.
     */
    @SuppressWarnings("unchecked")
    private static void applyDelegatingSerializer(DefaultKafkaProducerFactory<?, ?> factory) {
        // LinkedHashMap, not Map.of: the delegates are matched in iteration order, and Object
        // matches everything - so byte[] has to be offered first or it would never be reached.
        Map<Class<?>, Serializer<?>> delegates = new LinkedHashMap<>();
        delegates.put(byte[].class, new ByteArraySerializer());
        delegates.put(Object.class, new JacksonJsonSerializer<>());

        ((DefaultKafkaProducerFactory<Object, Object>) factory).setValueSerializer(
                // true = match by assignability rather than by exact class. Without it the Object
                // entry is a catch-all that catches nothing, and every payload whose class is not
                // listed literally fails to serialize with "Send failed" and no further detail.
                new DelegatingByTypeSerializer(delegates, true));
    }
}
