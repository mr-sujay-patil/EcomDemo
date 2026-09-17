package com.ecomdemo.messaging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.support.serializer.DelegatingByTopicDeserializer;
import org.springframework.kafka.support.serializer.DelegatingByTopicSerialization;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What notification-service's Kafka consumer binds to.
 *
 * <p>Every mistake available here is silent, which is why the settings are asserted rather than
 * trusted. A missing {@code ErrorHandlingDeserializer} presents as a consumer that has stopped, not
 * as an error: the failure happens inside the poll, so the same batch is fetched and fails forever
 * with no retry, no dead letter and no progress.
 *
 * <p>No broker is contacted. The consumer factory holds its configuration and opens no connection
 * until something asks it to poll.
 */
class KafkaConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
            .withPropertyValues("spring.profiles.active=dev");

    private static Map<String, Object> compose;

    @BeforeAll
    static void readCompose() throws IOException {
        compose = new Yaml().load(Files.readString(repositoryRoot().resolve("compose.yaml")));
    }

    /**
     * Walks up from the module directory to the repository root.
     *
     * <p>Maven runs this from the module, and compose.yaml belongs to no module. Hard-coding
     * {@code ../../} would work until somebody moved the module.
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.exists(candidate.resolve("compose.yaml"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("Could not find the repository root");
        }
        return candidate;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> service(String name) {
        return (Map<String, Object>) ((Map<String, Object>) compose.get("services")).get(name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> environment(String serviceName) {
        return (Map<String, Object>) service(serviceName).get("environment");
    }

    @Nested
    class TheConsumer {

        @Test
        void consumer_always_wrapsItsDeserializersInErrorHandlingDeserializer() {
            runner.run(context -> {
                Map<String, Object> props = context.getBean(KafkaProperties.class)
                        .buildConsumerProperties();

                // THEN a malformed message fails the record, not the poll.
                //
                // This is the most important assertion in this class. Without the wrapper, a poison
                // message throws inside poll(), the container asks for the same batch again, and the
                // partition stops advancing forever - no retry, no dead letter, no error, just a
                // consumer that appears healthy and has silently stopped.
                // The two deserializer keys bind as Class, the two delegate keys as String -
                // Boot resolves the former through its own property binding and passes the latter
                // straight through to the Kafka client, which resolves them itself.
                assertThat(props)
                        .containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                                ErrorHandlingDeserializer.class)
                        .containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                                ErrorHandlingDeserializer.class)
                        .containsEntry(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS,
                                DelegatingByTopicDeserializer.class.getName());
            });
        }

        @Test
        void consumer_always_readsTheDeadLetterTopicAsRawBytes() {
            runner.run(context -> {
                Map<String, Object> props = context.getBean(KafkaProperties.class)
                        .buildConsumerProperties();

                // THEN the DLT gets ByteArrayDeserializer and everything else gets Jackson.
                //
                // A record is in the dead-letter topic precisely because it could not be turned into
                // an OrderPlacedEvent. Pointing that consumer at a JSON deserializer means it fails
                // on exactly the payload it exists to report: @DltHandler never runs and nothing is
                // logged. ByteArrayDeserializer cannot fail.
                assertThat(props.get(DelegatingByTopicSerialization.VALUE_SERIALIZATION_TOPIC_CONFIG))
                        .asString()
                        .isEqualTo("orders\\.placed-dlt:" + ByteArrayDeserializer.class.getName());

                // Everything else goes through the default rather than through a second pattern.
                // Patterns are matched in a map's iteration order, not the order they are written
                // in, so a ".+" entry would not be a fallback - it would also match the DLT and win
                // roughly half the time. That is how this was found: the integration test passed
                // while the same configuration in a container sent the DLT through Jackson.
                assertThat(props)
                        .containsEntry(DelegatingByTopicSerialization.VALUE_SERIALIZATION_TOPIC_DEFAULT,
                                JacksonJsonDeserializer.class.getName());
            });
        }

        @Test
        void consumer_always_trustsOnlyTheEventPackage() {
            runner.run(context -> {
                Map<String, Object> props = context.getBean(KafkaProperties.class)
                        .buildConsumerProperties();

                // THEN the JSON deserializer will construct types from one package only. Trusting
                // "*" lets whoever can write to the topic name any class on the classpath.
                //
                // Note the package: THIS service's, not the producer's. The __TypeId__ header on the
                // wire names com.ecomdemo.order.event.OrderPlacedEvent, a class that does not exist
                // in this JVM - so the header is ignored (spring.json.use.type.headers: false) and
                // the payload is read into this service's own record. A consumer that needed the
                // producer's class names on its classpath would be sharing a jar, not a message.
                assertThat(props).containsEntry(JacksonJsonDeserializer.TRUSTED_PACKAGES,
                        "com.ecomdemo.notification.event");
            });
        }

        @Test
        void consumer_always_commitsOffsetsItself() {
            runner.run(context -> {
                Map<String, Object> props = context.getBean(KafkaProperties.class)
                        .buildConsumerProperties();

                // THEN auto-commit is off, so the offset moves after the listener returns rather
                // than on a timer. Auto-commit acknowledges records that were received, not records
                // that were handled - which turns at-least-once into at-most-once, silently.
                assertThat(props)
                        .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                        .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                        .containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "ecomdemo-notifications");
            });
        }
    }
}
