package com.ecomdemo.support;

import com.ecomdemo.auth.dto.LoginRequest;
import com.ecomdemo.auth.dto.TokenResponse;
import com.ecomdemo.customer.dto.CustomerResponse;
import com.ecomdemo.customer.dto.RegisterRequest;

import org.junit.jupiter.api.BeforeEach;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The base for customer-service's integration tests: a real PostgreSQL, a real Tomcat, and an HTTP
 * client pointed at it.
 *
 * <h2>What the split did to this class</h2>
 *
 * There used to be one {@code AbstractPostgresIT} for the whole application, and it started
 * PostgreSQL, Redis <em>and</em> Kafka - because the one application under test used all three. Each
 * service now has its own base class starting only what that service actually talks to, which is not
 * tidiness: it is the first place the split pays for itself. customer-service has no cache and
 * publishes no events, so its test suite starts one container instead of three and its context
 * cannot fail for a reason involving a broker.
 *
 * <p>It is also the first place the split <em>costs</em> something. This class can still register a
 * user and log in, because those endpoints are here. The other four services cannot - there is no
 * login endpoint in catalog-service - so they mint tokens with
 * {@link com.ecomdemo.shared.testsupport.TestTokens} instead. Getting a realistic credential used to
 * be free and is now a decision.
 *
 * <h2>One container for the whole run</h2>
 *
 * A {@code static} field started from a {@code static} initialiser - the "singleton container"
 * pattern. A static field initialises once per JVM, so every subclass shares one database and pays
 * the startup cost once. {@code @Testcontainers} with {@code @Container} would instead start and stop
 * one per test class.
 *
 * <p>Nothing stops it explicitly: Testcontainers' Ryuk sidecar removes the containers when the JVM
 * exits, including when it exits badly, which a shutdown hook could not.
 *
 * <h2>Which profile</h2>
 *
 * Deliberately none: these run under the default {@code dev} profile, the configuration the service
 * actually runs with, with only the connection details swapped. So {@code ddl-auto: validate} applies
 * and Flyway builds the schema - this service's own V1, against real PostgreSQL, on every build.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractCustomerServiceIT {

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    static {
        POSTGRES.start();
    }

    @LocalServerPort
    private int port;

    /**
     * The seeded administrator's credentials, from V1. The password is documented in the README; it
     * unlocks a throwaway container and nothing else.
     */
    protected static final String ADMIN_EMAIL = "admin@ecomdemo.local";
    protected static final String ADMIN_PASSWORD = "admin123";
    protected static final String CUSTOMER_PASSWORD = "password123";

    /** No credentials at all. Use it to assert that something is public - or that it is not. */
    protected RestTestClient anonymous;

    /** Holding a token for a CUSTOMER registered fresh for each test. */
    protected RestTestClient client;

    /** The id of that customer, for assertions that need to know whose data it is. */
    protected Long customerId;

    /** That customer's email, for logging in again. */
    protected String customerEmail;

    @BeforeEach
    void bindClientsToRunningServer() {
        anonymous = clientFor(null);

        // A fresh account per test. Every integration test in the run shares one container, so a
        // reused account would mean one test's state arriving in the next test's assertions.
        customerEmail = "it-" + System.nanoTime() + "@ecomdemo.local";
        customerId = anonymous.post().uri("/api/customers/register")
                .body(new RegisterRequest(customerEmail, CUSTOMER_PASSWORD, "Integration Test Shopper"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(CustomerResponse.class)
                .returnResult().getResponseBody()
                .id();

        client = clientFor(login(customerEmail, CUSTOMER_PASSWORD));
    }

    /** Exchanges credentials for a token, the way any real client of this system would start. */
    protected String login(String email, String password) {
        return anonymous.post().uri("/api/auth/login")
                .body(new LoginRequest(email, password))
                .exchange()
                .expectStatus().isOk()
                .expectBody(TokenResponse.class)
                .returnResult().getResponseBody()
                .accessToken();
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
