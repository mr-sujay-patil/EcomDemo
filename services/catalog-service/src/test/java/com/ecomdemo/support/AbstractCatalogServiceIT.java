package com.ecomdemo.support;

import com.ecomdemo.shared.security.Role;
import com.ecomdemo.shared.testsupport.TestTokens;

import org.junit.jupiter.api.BeforeEach;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The base for catalog-service's integration tests: its own PostgreSQL, its own Redis, a real
 * Tomcat, and clients holding tokens.
 *
 * <h2>Where the tokens come from</h2>
 *
 * Not from logging in - there is no login endpoint in this service. {@link TestTokens} mints them
 * with a test keypair and the {@link JwtDecoder} below verifies them with the matching public key,
 * which is precisely the arrangement the running service has: a decoder built from a key fetched
 * from customer-service, and no other relationship with it whatsoever.
 *
 * <p>That substitution is honest rather than a shortcut. The property under test is that this
 * service authorizes requests from a signed token alone. Booting customer-service alongside it to
 * obtain a token would test a deployment, take ten times as long, and prove less: it would no longer
 * be possible to tell whether catalog-service was verifying the signature or simply trusting
 * whatever arrived.
 *
 * <h2>One container set for the whole run</h2>
 *
 * Static fields started from a static initialiser - the singleton container pattern - so every
 * subclass shares one database and one Redis and pays the startup cost once. Ryuk removes them when
 * the JVM exits.
 *
 * <p>Note that there are two containers here and three in the monolith's version of this class:
 * catalog-service publishes no events, so no broker is started and no test in this module can fail
 * for a reason involving Kafka. Shrinking the blast radius of a test suite is one of the split's
 * quieter dividends.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Imported explicitly rather than relying on Boot to discover the nested class: that discovery
// applies to a @TestConfiguration nested in the test class being run, not one inherited from an
// abstract base. Left implicit, every subclass would try to fetch customer-service's JWK set at
// first use and fail with a connection error that says nothing about why.
@Import(AbstractCatalogServiceIT.TestJwtDecoder.class)
public abstract class AbstractCatalogServiceIT {

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    /**
     * Redis, for the cache this service expects from Phase 13 on.
     *
     * <p>A plain {@code GenericContainer} rather than a dedicated module: Testcontainers 2.x has no
     * Redis module in its BOM, and the community {@code com.redis:testcontainers-redis} targets the
     * 1.x line. {@code @ServiceConnection(name = "redis")} is what tells Spring Boot which kind of
     * service this generic container is.
     */
    @ServiceConnection(name = "redis")
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    /**
     * Replaces the decoder Boot would have built from customer-service's JWK set URL.
     *
     * <p>Without it the context would try to fetch that URL at first use and fail - there is no
     * customer-service in this test, and there should not be.
     */
    @TestConfiguration
    static class TestJwtDecoder {

        @Bean
        JwtDecoder jwtDecoder() {
            return TestTokens.decoder();
        }
    }

    @LocalServerPort
    private int port;

    /** No credentials at all. Use it to assert that something is public - or that it is not. */
    protected RestTestClient anonymous;

    /** Holding an ADMIN token. The only client that may write to the catalogue. */
    protected RestTestClient admin;

    /** Holding a CUSTOMER token, for asserting that browsing works and writing does not. */
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
}
