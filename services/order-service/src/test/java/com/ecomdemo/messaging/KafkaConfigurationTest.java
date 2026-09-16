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
 * What order-service's Kafka producer binds to, and what the broker in compose is told.
 *
 * <p>The same reasoning as {@code DatasourceProfileTest}: these settings are strings in a YAML file
 * until something reads them, and every mistake available here is silent. {@code acks=1} instead of
 * {@code all} loses data only when a broker fails. A replication factor of 3 on one broker produces
 * a consumer group that hangs. None of them fails a build, and none of them fails
 * {@code docker compose up}.
 *
 * <p>The consumer half of this class moved to notification-service in Phase 20, along with the
 * consumer itself. This service only produces now, which is the shape of the split: it publishes a
 * fact and never learns who read it.
 *
 * <p>No broker is contacted. The producer factory holds its configuration and opens no connection
 * until something asks it to send, exactly as HikariCP opens no connection until a query needs one.
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
    class TheProducer {

        @Test
        void producer_always_waitsForEveryInSyncReplica() {
            // GIVEN the dev configuration
            // WHEN the producer properties are bound
            runner.run(context -> {
                Map<String, Object> props = context.getBean(KafkaProperties.class)
                        .buildProducerProperties();

                // THEN acks=all. With acks=1 the leader can acknowledge and then fail before any
                // follower has copied the record, and the new leader has never seen it - a write
                // that was reported successful and is gone.
                assertThat(props).containsEntry(ProducerConfig.ACKS_CONFIG, "all");
            });
        }

        @Test
        void producer_always_isIdempotentSoRetriesDoNotDuplicate() {
            runner.run(context -> {
                Map<String, Object> props = context.getBean(KafkaProperties.class)
                        .buildProducerProperties();

                // THEN a retry cannot write the record twice. Without this, retries > 0 means a
                // send whose acknowledgement was lost in the network is written again.
                assertThat(props).containsEntry("enable.idempotence", "true");
                assertThat(props).containsEntry(ProducerConfig.RETRIES_CONFIG, 10);
            });
        }

        @Test
        void producer_always_givesUpQuicklyWhenThereIsNoBroker() {
            runner.run(context -> {
                Map<String, Object> props = context.getBean(KafkaProperties.class)
                        .buildProducerProperties();

                // THEN send() blocks for at most 5s waiting for metadata, not the 60s default.
                // This is a blocking wait on the checkout thread: the default would mean a broker
                // outage freezes every purchase for a minute before the event fails.
                assertThat(props).containsEntry("max.block.ms", "5000");
            });
        }
    }

    @Nested
    class TheBrokerInCompose {

        @Test
        void kafka_always_runsInKraftModeWithNoZookeeper() {
            // GIVEN the compose stack
            // WHEN the services are listed
            @SuppressWarnings("unchecked")
            Map<String, Object> services = (Map<String, Object>) compose.get("services");

            // THEN there is no ZooKeeper at all - the metadata lives in Kafka's own Raft log, which
            // is the entire point of KRaft
            assertThat(services).doesNotContainKey("zookeeper");
            assertThat(environment("kafka"))
                    .containsEntry("KAFKA_PROCESS_ROLES", "broker,controller")
                    .containsEntry("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@kafka:9093");
        }

        @Test
        void kafka_always_forcesTheReplicationFactorsDownToOne() {
            // GIVEN one broker
            // WHEN the internal-topic settings are read
            Map<String, Object> env = environment("kafka");

            // THEN they say 1, not the default 3. A topic needing three replicas on one broker never
            // becomes available, and __consumer_offsets is created lazily - so the broker starts
            // fine, the app connects fine, and the first consumer group hangs.
            assertThat(env)
                    .containsEntry("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", 1)
                    .containsEntry("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", 1)
                    .containsEntry("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", 1);
        }

        @Test
        void kafka_always_advertisesItselfByServiceNameNotLocalhost() {
            // GIVEN the listener configuration
            Map<String, Object> env = environment("kafka");

            // THEN clients are told to reach the broker at `kafka`.
            //
            // The advertised address is not cosmetic: a client's first connection only asks who
            // leads which partition, and every connection after that goes to whatever came back.
            // `localhost` here would send the app container to itself.
            assertThat(env).containsEntry("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://kafka:9092");
            assertThat(env.get("KAFKA_LISTENERS").toString()).contains("CONTROLLER://");
        }

        @Test
        @SuppressWarnings("unchecked")
        void kafka_always_keepsItsLogInANamedVolume() {
            // GIVEN the broker's volumes
            List<String> volumes = (List<String>) service("kafka").get("volumes");

            // THEN the commit log survives `docker compose down`. Kafka is a log, not a cache:
            // dropping it loses every unconsumed event and rewinds every consumer group to zero.
            assertThat(volumes).anyMatch(volume -> volume.startsWith("kafkadata:"));
            assertThat((Map<String, Object>) compose.get("volumes")).containsKey("kafkadata");
            // And the broker is pointed at that mount rather than the image's default under /tmp,
            // which lives in the container's writable layer and dies with it.
            assertThat(environment("kafka")).containsEntry("KAFKA_LOG_DIRS", "/var/lib/kafka/data");
        }

        @Test
        @SuppressWarnings("unchecked")
        void kafka_always_isReachedByServiceNameAndWaitedForByBothServicesThatUseIt() {
            // GIVEN the two services that speak to the broker - and only those two. catalog,
            // inventory and customer have no Kafka dependency at all, which is itself worth pinning:
            // a service that waits for a broker it never uses is a service that cannot start while
            // the broker is down, for no reason.
            for (String kafkaUser : List.of("order-service", "notification-service")) {
                Map<String, Object> dependsOn = (Map<String, Object>) service(kafkaUser).get("depends_on");

                // THEN it waits for a broker that is answering, not merely started
                assertThat((Map<String, Object>) dependsOn.get("kafka"))
                        .as("%s must wait for the broker", kafkaUser)
                        .containsEntry("condition", "service_healthy");
                assertThat(environment(kafkaUser))
                        .containsEntry("SPRING_KAFKA_BOOTSTRAP_SERVERS", "kafka:9092");
            }

            for (String other : List.of("customer-service", "catalog-service", "inventory-service")) {
                assertThat(environment(other))
                        .as("%s does not use Kafka and must not be configured for it", other)
                        .doesNotContainKey("SPRING_KAFKA_BOOTSTRAP_SERVERS");
            }
        }

        @Test
        void kafka_always_isNotPublishedToTheHost() {
            // GIVEN the broker service
            // THEN it has no ports at all. It is reachable on the compose network only - and
            // publishing 9092 would not work anyway, because the advertised address says `kafka`,
            // which a host-side client cannot resolve.
            assertThat(service("kafka")).doesNotContainKey("ports");
        }

        @Test
        @SuppressWarnings("unchecked")
        void kafkaUi_always_pointsAtTheBrokerAndAvoidsEveryServicesPort() {
            // GIVEN the inspection UI
            Map<String, Object> ui = service("kafka-ui");

            // THEN it reaches the broker by service name and publishes on 8086.
            //
            // NOT 8081, which it used until Phase 20 and which customer-service now owns. A port
            // collision in compose is a container that exits with a one-line error most people
            // scroll past, and the symptom would be "login stopped working" rather than anything
            // about Kafka.
            assertThat(environment("kafka-ui"))
                    .containsEntry("KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS", "kafka:9092");
            assertThat((List<String>) ui.get("ports")).containsExactly("8086:8080");
        }

        @Test
        @SuppressWarnings("unchecked")
        void kafkaUi_always_staysBehindTheObservabilityProfile() {
            // THEN it is opt-in. Three more JVMs on a Docker VM already running six is the difference
            // between a stack that idles and one that swaps - Phase 17 measured the four-JVM version
            // of this problem at 428% CPU on an idle broker.
            assertThat((List<String>) service("kafka-ui").get("profiles"))
                    .containsExactly("observability");
        }
    }
}
