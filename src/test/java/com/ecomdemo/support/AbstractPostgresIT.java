package com.ecomdemo.support;

import org.junit.jupiter.api.BeforeEach;

import org.springframework.boot.test.context.SpringBootTest;
import com.ecomdemo.auth.dto.LoginRequest;
import com.ecomdemo.auth.dto.TokenResponse;
import com.ecomdemo.customer.dto.CustomerResponse;
import com.ecomdemo.customer.dto.RegisterRequest;

import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The base every integration test extends: a real PostgreSQL, a real Tomcat, and an HTTP client
 * pointed at it.
 *
 * <h2>One container for the whole run</h2>
 *
 * The container is a {@code static} field started from a {@code static} initialiser, which is the
 * "singleton container" pattern. A static field initialises once per JVM, so every subclass of this
 * class shares the same database and pays the ~2 second startup exactly once.
 *
 * <p>The obvious-looking alternative - {@code @Testcontainers} with {@code @Container} - would do
 * the opposite. That hands the lifecycle to JUnit, which starts the container before each test class
 * and stops it after, so three integration tests would mean three containers started and stopped in
 * sequence. Correct, but slower for no benefit here.
 *
 * <p>Nothing stops the container explicitly. Testcontainers starts a small sidecar (Ryuk) that
 * watches the JVM and removes the containers it created when that JVM exits, including when it exits
 * badly. A {@code stop()} in a shutdown hook would be strictly worse: it cannot run if the process
 * is killed.
 *
 * <h2>@ServiceConnection</h2>
 *
 * The container gets a random host port, which nothing could know in advance.
 * {@code @ServiceConnection} reads the started container and contributes the JDBC URL, username and
 * password to the {@code Environment} before the context refreshes - replacing the hand-written
 * {@code @DynamicPropertySource} block this would otherwise need, and replacing the three
 * {@code POSTGRES_*} values from {@code application-dev.yml} for the duration of the test.
 *
 * <h2>Which profile</h2>
 *
 * Deliberately none: these tests run under the default {@code dev} profile, the same configuration
 * the application runs with, with only the connection details swapped. So {@code ddl-auto: validate}
 * applies and Flyway builds the schema - which means the migrations themselves are exercised against
 * real PostgreSQL on every build, something no test could check before this phase.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractPostgresIT {

    /**
     * Pinned to the same image the README's `docker run` uses. An integration test claiming to run
     * "on PostgreSQL" while running on a different major version than production would be a subtler
     * version of the problem this phase exists to solve.
     */
    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    /**
     * Redis, for the cache the application expects from Phase 13 on.
     *
     * <p>A plain {@code GenericContainer} rather than a dedicated module: Testcontainers 2.x has no
     * Redis module in its BOM, and the community {@code com.redis:testcontainers-redis} targets the
     * 1.x line. {@code @ServiceConnection(name = "redis")} is what tells Spring Boot which kind of
     * service this generic container is, so it can contribute the host and port the same way it does
     * for PostgreSQL.
     *
     * <p>Every integration test now gets one, because the integration tests run the production
     * configuration and that configuration has a cache in it. Testing without one would test a
     * different application.
     */
    @ServiceConnection(name = "redis")
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @LocalServerPort
    private int port;

    /**
     * The seeded administrator's credentials, from V6. The password is documented in the README; it
     * unlocks a throwaway container and nothing else.
     */
    private static final String ADMIN_EMAIL = "admin@ecomdemo.local";
    private static final String ADMIN_PASSWORD = "admin123";
    private static final String CUSTOMER_PASSWORD = "password123";

    /**
     * No credentials at all. Use it to assert that something is public - or that it is not.
     */
    protected RestTestClient anonymous;

    /** Holding a token for the seeded ADMIN. The only client that may write to the catalogue. */
    protected RestTestClient admin;

    /**
     * Holding a token for a CUSTOMER registered fresh for each test.
     *
     * <p>Fresh matters: carts and orders belong to a user now, and every integration test in the run
     * shares one container. Reusing an account would mean one test's cart contents arriving in the
     * next test's assertions.
     */
    protected RestTestClient client;

    /** The id of that customer, for assertions that need to know whose data it is. */
    protected Long customerId;

    @BeforeEach
    void bindClientsToRunningServer() {
        anonymous = clientFor(null);

        // Registered through the public endpoint rather than inserted directly, so every integration
        // test also exercises the registration path and the BCrypt hashing behind it.
        String email = "it-" + System.nanoTime() + "@ecomdemo.local";
        customerId = anonymous.post().uri("/api/customers/register")
                .body(new RegisterRequest(email, CUSTOMER_PASSWORD, "Integration Test Shopper"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(CustomerResponse.class)
                .returnResult().getResponseBody()
                .id();

        // Every client now logs in once and carries the token it was given. That is the whole shape
        // of the change in Phase 9: credentials are presented exactly once, and everything afterwards
        // presents a signed statement about who logged in.
        admin = clientFor(login(ADMIN_EMAIL, ADMIN_PASSWORD));
        client = clientFor(login(email, CUSTOMER_PASSWORD));
    }

    /** Exchanges credentials for a token, the way any real client of this API would start. */
    protected String login(String email, String password) {
        return anonymous.post().uri("/api/auth/login")
                .body(new LoginRequest(email, password))
                .exchange()
                .expectStatus().isOk()
                .expectBody(TokenResponse.class)
                .returnResult().getResponseBody()
                .accessToken();
    }

    /**
     * Bound to the real server rather than to MockMvc, so requests travel over a socket through the
     * whole stack - Tomcat, the security filter chain, Jackson, the controller advice.
     *
     * <p>The bearer token goes on every request. There is still no session - each request carries
     * its own proof - but the proof is now a signed token rather than the password itself.
     */
    protected RestTestClient clientFor(String accessToken) {
        // var, because RestTestClient.Builder is self-referentially generic - naming the raw type
        // erases it and the fluent methods stop resolving.
        var builder = RestTestClient.bindToServer().baseUrl("http://localhost:" + port);
        return accessToken == null
                ? builder.build()
                : builder.defaultHeaders(headers -> headers.setBearerAuth(accessToken)).build();
    }
}
