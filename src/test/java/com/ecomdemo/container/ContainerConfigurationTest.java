package com.ecomdemo.container;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the properties of the container setup that are easy to lose and expensive to lose quietly.
 *
 * <p>Docker configuration is normally verified by running it, and these tests do not replace that -
 * {@code docker compose up} is what proves the system works. What they catch is the regression that
 * still runs perfectly: deleting {@code USER ecomdemo} produces a working container that happens to
 * run as root; dropping the layered extraction produces a working image that pushes 59 MB per commit;
 * changing the healthcheck condition produces a stack that works on a fast machine and fails on a
 * slow one. Every one of those passes {@code up}.
 *
 * <p>{@code compose.yaml} is parsed rather than string-matched, so an assertion is about structure
 * rather than about whether a line was reformatted. The Dockerfile has no parser here, so comments
 * are stripped before any assertion runs - the first version of this class matched the raw text, and
 * commenting out {@code USER ecomdemo} left every test green, because {@code # USER ecomdemo} still
 * contains the string. A test that cannot see the regression it exists to catch is worse than none.
 */
class ContainerConfigurationTest {

    private static String dockerfile;
    /** The same file with comments removed, for assertions about instruction order. */
    private static String instructions;
    private static String dockerignore;
    private static Map<String, Object> compose;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void readTheFiles() throws IOException {
        dockerfile = Files.readString(Path.of("Dockerfile"));
        instructions = dockerfile.lines()
                .filter(line -> !line.stripLeading().startsWith("#"))
                .reduce("", (a, b) -> a + "\n" + b);
        dockerignore = Files.readString(Path.of(".dockerignore"));
        compose = new Yaml().load(Files.readString(Path.of("compose.yaml")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> service(String name) {
        return (Map<String, Object>) ((Map<String, Object>) compose.get("services")).get(name);
    }

    @Nested
    class TheImageIsSmallAndCacheable {

        @Test
        void dockerfile_always_buildsInOneStageAndShipsAnother() {
            // GIVEN the Dockerfile
            // THEN there is a named build stage, and the final stage is a JRE rather than a JDK -
            // nothing in the running image compiles anything, and the compiler is the bulk of a JDK
            assertThat(instructions).contains("AS build");
            assertThat(instructions).contains("FROM eclipse-temurin:21-jre-alpine");
        }

        @Test
        void dockerfile_always_extractsTheLayeredJar() {
            // THEN the layers are exploded and copied one at a time, which is what lets Docker cache
            // the 59 MB of dependencies separately from the 426 kB of application code
            assertThat(instructions).contains("-Djarmode=tools");
            assertThat(instructions).contains("extract --layers");

            assertThat(instructions)
                    .as("one COPY per layer, or the caching is pointless")
                    .contains("/extracted/dependencies/")
                    .contains("/extracted/spring-boot-loader/")
                    .contains("/extracted/snapshot-dependencies/")
                    .contains("/extracted/application/");
        }

        @Test
        void dockerfile_always_copiesThePomBeforeTheSources() {
            // THEN dependency resolution is cached independently of source changes. Copying
            // everything at once would re-download the dependencies on every edited Java file.
            // Comments are stripped first: the explanation above these instructions mentions
            // "COPY src/" by name, and matching prose instead of instructions would have this test
            // asserting the order of the documentation.
            int pomCopy = instructions.indexOf("COPY mvnw pom.xml");
            int dependencyFetch = instructions.indexOf("dependency:go-offline");
            int sourceCopy = instructions.indexOf("COPY src/");

            assertThat(pomCopy).isPositive();
            assertThat(dependencyFetch).isGreaterThan(pomCopy);
            assertThat(sourceCopy)
                    .as("sources must be copied after dependencies are resolved")
                    .isGreaterThan(dependencyFetch);
        }

        @Test
        void dockerignore_always_excludesTheHeavyAndTheSecret() {
            // THEN the build context stays small and carries nothing sensitive. frontend/ alone is
            // ~41 MB of node_modules that would otherwise be shipped to the daemon on every build.
            assertThat(dockerignore).contains("frontend/").contains("target/").contains(".git/");
            assertThat(dockerignore).contains(".env");
        }
    }

    @Nested
    class TheContainerIsNotRoot {

        @Test
        void dockerfile_always_switchesToAnUnprivilegedUser() {
            // THEN a user is created and selected. Root in a container is root on the host kernel -
            // the isolation is namespaces, not a virtual machine.
            assertThat(instructions).contains("adduser");
            assertThat(instructions).contains("USER ecomdemo");
        }

        @Test
        void dockerfile_always_switchesUserAfterCopyingFiles() {
            // THEN the COPYs still run as root and set ownership explicitly. Switching earlier would
            // mean the application could write to its own installation directory, which it never
            // needs to do.
            assertThat(instructions.indexOf("USER ecomdemo"))
                    .isGreaterThan(instructions.lastIndexOf("COPY --from=extract"));
            assertThat(instructions).contains("--chown=ecomdemo:ecomdemo");
        }
    }

    @Nested
    class TheJvmKnowsItIsInAContainer {

        @Test
        void dockerfile_always_raisesTheHeapCeiling() {
            // THEN the heap may use most of the container's memory. The JVM reads the container limit
            // but defaults to 25% of it, which leaves three quarters of the allowance unused.
            assertThat(instructions).contains("-XX:MaxRAMPercentage=75.0");
        }

        @Test
        void compose_always_givesTheAppAMemoryLimit() {
            // THEN the percentage above has something to be a percentage of. Without a limit the JVM
            // sizes its heap against the whole host.
            assertThat(service("app").toString()).contains("memory");
        }
    }

    @Nested
    class TheStackStartsInTheRightOrder {

        @Test
        @SuppressWarnings("unchecked")
        void compose_always_waitsForPostgresToBeHealthyNotMerelyStarted() {
            // GIVEN the app service
            Map<String, Object> dependsOn = (Map<String, Object>) service("app").get("depends_on");

            // THEN it waits for the database to answer, not just to exist. service_started is the
            // default and is what makes people add sleeps to entrypoints.
            assertThat(dependsOn).containsKey("postgres");
            assertThat(((Map<String, Object>) dependsOn.get("postgres")).get("condition"))
                    .isEqualTo("service_healthy");
        }

        @Test
        void compose_always_healthchecksBothServices() {
            assertThat(service("postgres")).containsKey("healthcheck");
            assertThat(service("app")).containsKey("healthcheck");
        }
    }

    @Nested
    class NetworkingAndPersistence {

        @Test
        @SuppressWarnings("unchecked")
        void compose_always_reachesPostgresByServiceNameNotLocalhost() {
            // GIVEN the app's environment
            Map<String, Object> environment = (Map<String, Object>) service("app").get("environment");
            String url = String.valueOf(environment.get("POSTGRES_URL"));

            // THEN the host is the service name. Each container has its own network namespace, so
            // localhost inside the app container is the app container - there is no database there.
            assertThat(url).contains("//postgres:5432/");
            assertThat(url).doesNotContain("localhost").doesNotContain("127.0.0.1");
        }

        @Test
        @SuppressWarnings("unchecked")
        void compose_always_keepsTheDatabaseInANamedVolume() {
            // GIVEN the postgres service
            List<String> volumes = (List<String>) service("postgres").get("volumes");

            // THEN the data lives outside the container's writable layer, so it survives
            // `docker compose down`. The mount path is PostgreSQL 18's: mounting the older
            // /var/lib/postgresql/data succeeds and silently persists nothing.
            assertThat(volumes).anyMatch(v -> v.endsWith(":/var/lib/postgresql"));
            assertThat(compose).containsKey("volumes");
        }
    }

    @Nested
    class NoSecretsAreCommitted {

        @Test
        @SuppressWarnings("unchecked")
        void compose_always_takesItsSecretsFromTheEnvironment() {
            // GIVEN the app's environment
            Map<String, Object> environment = (Map<String, Object>) service("app").get("environment");

            // THEN neither secret is a literal, and both use the ${VAR:?message} form so compose
            // refuses to start rather than falling back to a value nobody chose
            assertThat(String.valueOf(environment.get("JWT_SECRET")))
                    .startsWith("${JWT_SECRET:?");
            assertThat(String.valueOf(environment.get("POSTGRES_PASSWORD")))
                    .startsWith("${POSTGRES_PASSWORD:?");
        }

        @Test
        void envExample_always_existsAndCarriesNoRealValues() throws IOException {
            // GIVEN the template a fresh clone copies
            String example = Files.readString(Path.of(".env.example"));

            // THEN it names what is needed and supplies nothing usable
            assertThat(example).contains("POSTGRES_PASSWORD").contains("JWT_SECRET");
            assertThat(example).contains("change-me");
            assertThat(example).contains("openssl rand -base64 48");
        }

        @Test
        void dotEnv_always_staysOutOfGit() throws IOException {
            // THEN the real file is ignored. .env.example is the committed one.
            assertThat(Files.readString(Path.of(".gitignore")))
                    .contains("\n.env\n");
        }
    }
}
