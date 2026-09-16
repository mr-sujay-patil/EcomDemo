package com.ecomdemo.support;

import com.ecomdemo.shared.security.Role;
import com.ecomdemo.shared.testsupport.TestTokens;

import org.junit.jupiter.api.BeforeEach;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The base for notification-service's integration tests: its own PostgreSQL, a real Kafka, a real
 * Tomcat.
 *
 * <p>Kafka is real and not mocked, because this service's entire behaviour is driven by what comes
 * off a topic - the retry topics, the dead-letter routing and the offset commits are the thing under
 * test, and none of them exist in a mock.
 *
 * <p>order-service is absent entirely, and that is the point rather than a compromise. These tests
 * publish JSON to {@code orders.placed} themselves, which is exactly what a producer this service has
 * never heard of would do. If this suite needed order-service in order to produce a message, the two
 * would not be decoupled by the broker at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AbstractNotificationServiceIT.TestJwtDecoder.class)
public abstract class AbstractNotificationServiceIT {

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    /**
     * {@code org.testcontainers.kafka.KafkaContainer} - note the package. The class of the same name
     * in {@code org.testcontainers.containers} is the older Confluent-plus-ZooKeeper one, and both
     * are in the jar. This is the KRaft-native one running Apache's own image, pinned to the tag
     * compose.yaml uses.
     */
    @ServiceConnection
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.1");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @TestConfiguration
    static class TestJwtDecoder {

        @Bean
        JwtDecoder jwtDecoder() {
            return TestTokens.decoder();
        }
    }

    /**
     * Publishes raw strings onto the topic.
     *
     * <p>Boot's default producer serializers are String on both sides, and nothing in this service
     * configures otherwise - it is a consumer. That is convenient here and also correct: a test that
     * published an {@code OrderPlacedEvent} object through a JSON serializer would be testing this
     * service's ability to read its own writing. Sending the bytes a stranger would send is the only
     * version of this test that means anything.
     */
    @Autowired
    protected KafkaTemplate<String, String> kafkaTemplate;

    @LocalServerPort
    private int port;

    protected RestTestClient anonymous;

    /** Holding a CUSTOMER token for {@link #customerId}. */
    protected RestTestClient client;

    /** A distinct customer per test: every IT shares one container and commits as it goes. */
    protected Long customerId;

    @BeforeEach
    void bindClients() {
        customerId = 600_000L + (System.nanoTime() % 300_000L);
        anonymous = clientFor(null);
        client = clientFor(TestTokens.issue(customerId, "it-" + customerId + "@ecomdemo.local", Role.CUSTOMER));
    }

    protected RestTestClient clientFor(String accessToken) {
        // var, because RestTestClient.Builder is self-referentially generic - naming the raw type
        // erases it and the fluent methods stop resolving.
        var builder = RestTestClient.bindToServer().baseUrl("http://localhost:" + port);
        return accessToken == null
                ? builder.build()
                : builder.defaultHeaders(headers -> headers.setBearerAuth(accessToken)).build();
    }
}
