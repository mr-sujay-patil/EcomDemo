package com.ecomdemo.support;

import java.util.List;

import com.ecomdemo.order.client.CatalogClient;
import com.ecomdemo.order.client.CatalogProduct;
import com.ecomdemo.order.client.InventoryClient;
import com.ecomdemo.shared.security.Role;
import com.ecomdemo.shared.testsupport.TestTokens;

import org.junit.jupiter.api.BeforeEach;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * The base for order-service's integration tests: its own PostgreSQL, a real Kafka, a real Tomcat -
 * and its two neighbours replaced by mocks.
 *
 * <h2>Why the neighbours are mocked and the broker is real</h2>
 *
 * This is the most consequential testing decision in the phase, so it is worth the paragraph.
 *
 * <p>Kafka is real because order-service's relationship with it is asymmetric and cheap to stand up:
 * this service only produces, a broker in a container behaves like a broker, and the thing worth
 * checking - that a record with the right key and payload actually reaches a topic - cannot be
 * checked against a mock.
 *
 * <p>catalog-service and inventory-service are mocked at the client interface because the alternative
 * is to build and boot two more Spring applications for every test class. That would be slow enough
 * to discourage writing tests, and it would test the wrong thing: this suite's job is to check that
 * <em>order-service</em> behaves correctly given what its neighbours say, including when they say
 * something unhelpful. A mock can return a 409, refuse a connection, or hang - all of which are
 * outcomes that matter here and are awkward to provoke in a real service.
 *
 * <p><strong>What that leaves unchecked, honestly:</strong> whether the shapes these mocks return
 * match what catalog-service and inventory-service actually send. Nothing in this module can check
 * that. {@code CatalogClientContractTest} pins this side's expectation against a recorded payload,
 * inventory-service's {@code StockApiIT} pins the other side's, and the genuine end-to-end claim is
 * verified through Compose. A reader should treat "all five modules are green" as necessary and not
 * sufficient.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AbstractOrderServiceIT.TestJwtDecoder.class)
public abstract class AbstractOrderServiceIT {

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    /**
     * A real broker. {@code org.testcontainers.kafka.KafkaContainer} - note the package; the class of
     * the same name in {@code org.testcontainers.containers} is the older Confluent-plus-ZooKeeper
     * one, and both are in the jar. This is the KRaft-native one running Apache's own image, pinned
     * to the tag compose.yaml uses.
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

    /** catalog-service, stubbed. Override the stubbing in a test that needs different prices. */
    @MockitoBean
    protected CatalogClient catalogClient;

    /** inventory-service, stubbed. By default every reservation succeeds. */
    @MockitoBean
    protected InventoryClient inventoryClient;

    @LocalServerPort
    private int port;

    protected RestTestClient anonymous;

    /** Holding a CUSTOMER token for {@link #customerId}. */
    protected RestTestClient client;

    /**
     * A distinct customer per test.
     *
     * <p>Every integration test shares one container and commits as it goes, so a reused id would
     * mean one test's cart arriving in the next test's assertions. There is no registration endpoint
     * here to create a real one - and no users table - so the id is simply invented and signed into a
     * token. Nothing in this service checks that it corresponds to anybody, which is precisely the
     * property the missing foreign key describes.
     */
    protected Long customerId;

    /** The catalogue these tests pretend catalog-service is serving. */
    protected static final CatalogProduct KEYBOARD = new CatalogProduct(1L, "Mechanical Keyboard", TestFixtures.money("129.99"));
    protected static final CatalogProduct MOUSE = new CatalogProduct(2L, "Wireless Mouse", TestFixtures.money("49.50"));

    @BeforeEach
    void bindClientsAndStubNeighbours() {
        customerId = 500_000L + (System.nanoTime() % 400_000L);
        anonymous = clientFor(null);
        client = clientFor(TestTokens.issue(customerId, "it-" + customerId + "@ecomdemo.local", Role.CUSTOMER));

        given(catalogClient.findAll()).willReturn(List.of(KEYBOARD, MOUSE));
        given(catalogClient.findById(KEYBOARD.id())).willReturn(KEYBOARD);
        given(catalogClient.findById(MOUSE.id())).willReturn(MOUSE);
        // inventoryClient.reserve is void and does nothing by default, which is what "the reservation
        // succeeded" looks like. A test that wants it to fail stubs it with willThrow.
        given(inventoryClient.toString()).willCallRealMethod();
    }

    protected RestTestClient clientFor(String accessToken) {
        // var, because RestTestClient.Builder is self-referentially generic - naming the raw type
        // erases it and the fluent methods stop resolving.
        var builder = RestTestClient.bindToServer().baseUrl("http://localhost:" + port);
        return accessToken == null
                ? builder.build()
                : builder.defaultHeaders(headers -> headers.setBearerAuth(accessToken)).build();
    }

    /** Silences an unused-import warning for {@code any()} in subclasses that stub with matchers. */
    protected static <T> T anyOf(Class<T> type) {
        return any(type);
    }
}
