package com.ecomdemo.support;

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
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The base for inventory-service's integration tests: one PostgreSQL, a real Tomcat, and clients
 * holding minted tokens.
 *
 * <p>One container. This service has no cache and publishes no events, so there is no Redis and no
 * broker to start - which makes its integration suite the fastest in the repository and gives it the
 * smallest set of reasons to fail for something unrelated to stock.
 *
 * <p>Tokens come from {@link TestTokens} rather than from a login, because there is no login endpoint
 * here. The decoder below verifies them with the matching public key, which is exactly the
 * arrangement the running service has - a decoder built from a key fetched once from
 * customer-service, and no other relationship with it at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Imported explicitly rather than relying on Boot to discover the nested class: that discovery
// applies to a @TestConfiguration nested in the test class being run, not one inherited from an
// abstract base.
@Import(AbstractInventoryServiceIT.TestJwtDecoder.class)
public abstract class AbstractInventoryServiceIT {

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    static {
        POSTGRES.start();
    }

    @TestConfiguration
    static class TestJwtDecoder {

        @Bean
        JwtDecoder jwtDecoder() {
            return TestTokens.decoder();
        }
    }

    @LocalServerPort
    private int port;

    /** No credentials at all. Use it to assert that reading stock is public. */
    protected RestTestClient anonymous;

    /** Holding an ADMIN token: the only client that may set an absolute figure. */
    protected RestTestClient admin;

    /**
     * Holding a CUSTOMER token.
     *
     * <p>This is what order-service presents when it reserves stock: it forwards the shopper's own
     * token rather than holding a service credential of its own, so identity survives the hop.
     */
    protected RestTestClient customer;

    @BeforeEach
    void bindClientsToRunningServer() {
        anonymous = clientFor(null);
        admin = clientFor(TestTokens.issueAdmin());
        customer = clientFor(TestTokens.issue(7L, "shopper@ecomdemo.local", Role.CUSTOMER));
    }

    protected RestTestClient clientFor(String accessToken) {
        // var, because RestTestClient.Builder is self-referentially generic - naming the raw type
        // erases it and the fluent methods stop resolving.
        var builder = RestTestClient.bindToServer().baseUrl("http://localhost:" + port);
        return accessToken == null
                ? builder.build()
                : builder.defaultHeaders(headers -> headers.setBearerAuth(accessToken)).build();
    }

    /**
     * A product id nothing else in the run will touch.
     *
     * <p>Every integration test shares one container and commits as it goes, so an IT may not assume
     * an empty table or a particular starting quantity. Taking a fresh id per test is how these
     * assertions stay about the behaviour rather than about what ran first.
     */
    protected static Long freshProductId() {
        return 100_000L + (System.nanoTime() % 800_000L);
    }
}
