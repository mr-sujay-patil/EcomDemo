package com.ecomdemo.support;

import org.junit.jupiter.api.BeforeEach;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.client.RestTestClient;
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

    static {
        POSTGRES.start();
    }

    @LocalServerPort
    private int port;

    /**
     * Bound to the real server rather than to MockMvc, so requests travel over a socket through the
     * whole stack - Tomcat, Jackson, the filters, the controller advice.
     */
    protected RestTestClient client;

    @BeforeEach
    void bindClientToRunningServer() {
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }
}
