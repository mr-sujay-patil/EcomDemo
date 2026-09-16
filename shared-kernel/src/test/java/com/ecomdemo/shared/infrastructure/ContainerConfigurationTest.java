package com.ecomdemo.shared.infrastructure;

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
 *
 * <h2>Where this class lives, and why</h2>
 *
 * In shared-kernel, which is a library and has nothing to do with containers. That is a deliberate
 * compromise: these files are repository-level and belong to no module, and the alternatives were a
 * seventh module existing only to hold one test, or five copies of it. shared-kernel is the module
 * every service depends on and the first the reactor builds, so a broken Dockerfile fails the build
 * before five services are compiled against it.
 *
 * <p>It finds the repository root by walking up from the working directory, so it does not care
 * which module Maven happens to run it from.
 *
 * <h2>What Phase 20 changed here</h2>
 *
 * Every assertion that named {@code app} now runs against all five services, because a property that
 * holds for one image and not the other four is worse than one that holds for none - it looks fine
 * until the one service nobody checked is the one that runs as root.
 */
class ContainerConfigurationTest {

    /** The five deployables. Every structural assertion below runs against all of them. */
    static final List<String> SERVICES = List.of(
            "customer-service", "catalog-service", "inventory-service",
            "order-service", "notification-service");

    private static Path repositoryRoot;
    /** The Dockerfile with comments removed, for assertions about instruction order. */
    private static String instructions;
    private static String dockerignore;
    private static Map<String, Object> compose;

    @BeforeAll
    static void readTheFiles() throws IOException {
        repositoryRoot = findRepositoryRoot();
        instructions = read("Dockerfile").lines()
                .filter(line -> !line.stripLeading().startsWith("#"))
                .reduce("", (a, b) -> a + "\n" + b);
        dockerignore = read(".dockerignore");
        compose = new Yaml().load(read("compose.yaml"));
    }

    /**
     * Walks up from the working directory until it finds the file that marks the repository root.
     *
     * <p>Maven sets the working directory to the module being built, and this test asserts about
     * files that belong to no module. Hard-coding {@code ../} would work today and break the moment
     * the class moved.
     */
    private static Path findRepositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.exists(candidate.resolve("compose.yaml"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("Could not find compose.yaml above " + Path.of("").toAbsolutePath());
        }
        return candidate;
    }

    private static String read(String name) throws IOException {
        return Files.readString(repositoryRoot.resolve(name));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> service(String name) {
        Map<String, Object> found = (Map<String, Object>) ((Map<String, Object>) compose.get("services")).get(name);
        assertThat(found).as("compose.yaml has no service named %s", name).isNotNull();
        return found;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> environmentOf(String name) {
        return (Map<String, Object>) service(name).get("environment");
    }

    @Nested
    class TheImageIsSmallAndCacheable {

        @Test
        void dockerfile_always_buildsInOneStageAndShipsAnother() {
            // GIVEN the Dockerfile
            // THEN there is a named build stage, and the final stage is a JRE rather than a JDK -
            // nothing in the running image compiles anything, and the compiler is the bulk of a JDK
            assertThat(instructions)
                    .contains("AS build")
                    .contains("FROM eclipse-temurin:21-jre-alpine");
        }

        @Test
        void dockerfile_always_extractsTheLayeredJar() {
            // THEN the layers are exploded and copied one at a time, which is what lets Docker cache
            // the 59 MB of dependencies separately from the 426 kB of application code
            // One chain: .as() applies from where it appears onwards, so the description still
            // belongs to the layer assertions rather than to the whole thing.
            assertThat(instructions)
                    .contains("-Djarmode=tools")
                    .contains("extract --layers")
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
            // The exact instruction that copies the sources, not merely one that starts with
            // "COPY services/" - the seven POM copies do too, and they come BEFORE the dependency
            // resolution by design. Matching loosely made this assertion pass on the wrong line.
            int sourceCopy = instructions.indexOf("COPY services/ services/");

            assertThat(pomCopy).isPositive();
            assertThat(dependencyFetch).isGreaterThan(pomCopy);
            assertThat(sourceCopy)
                    .as("sources must be copied after dependencies are resolved")
                    .isGreaterThan(dependencyFetch);

            // Every module's POM, not just the root one. Maven cannot read the reactor without them,
            // so a forgotten line here does not break caching - it breaks the build, after the
            // dependency download, in a way that looks like a Maven problem.
            for (String service : SERVICES) {
                assertThat(instructions)
                        .as("%s's POM must be copied before dependency resolution", service)
                        .contains("COPY services/" + service + "/pom.xml");
            }
            assertThat(instructions).contains("COPY shared-kernel/pom.xml");
        }

        @Test
        void dockerignore_always_excludesTheHeavyAndTheSecret() {
            // THEN the build context stays small and carries nothing sensitive. frontend/ alone is
            // ~41 MB of node_modules that would otherwise be shipped to the daemon on every build.
            assertThat(dockerignore)
                    .contains("frontend/")
                    .contains("target/")
                    // Seven modules means seven target/ directories; the bare pattern only matches
                    // the one at the root.
                    .contains("**/target/")
                    .contains(".git/")
                    .contains(".env");
        }
    }

    @Nested
    class OneDockerfileBuildsFiveImages {

        @Test
        void dockerfile_always_takesTheServiceAsABuildArgument() {
            // THEN the module whose jar goes into the image is a parameter, not a hard-coded path.
            // The ARG has to be redeclared in the stage that uses it: an ARG is scoped to its stage,
            // which is one of the more surprising things about Dockerfiles and fails by substituting
            // an empty string rather than by erroring.
            assertThat(instructions)
                    .contains("ARG SERVICE")
                    .contains("/build/services/${SERVICE}/target/*.jar");
        }

        @Test
        @SuppressWarnings("unchecked")
        void compose_always_passesItsOwnServiceNameAsThatArgument() {
            // THEN each of the five asks for its own jar. Getting this wrong builds five identical
            // images that all run the same service, and the stack comes up with four services
            // answering on ports nothing expects them on.
            for (String service : SERVICES) {
                Map<String, Object> build = (Map<String, Object>) service(service).get("build");
                Map<String, Object> args = (Map<String, Object>) build.get("args");
                assertThat(args)
                        .as("%s must build itself", service)
                        .containsEntry("SERVICE", service);
            }
        }

        @Test
        void compose_never_keepsTheOldMonolithService() {
            // THEN `app` is gone rather than left behind alongside the five. A stale service
            // definition would still build, still start, and still bind port 8080.
            assertThat((Map<String, Object>) compose.get("services"))
                    .doesNotContainKey("app");
        }
    }

    @Nested
    class TheContainerIsNotRoot {

        @Test
        void dockerfile_always_switchesToAnUnprivilegedUser() {
            // THEN a user is created and selected. Root in a container is root on the host kernel -
            // the isolation is namespaces, not a virtual machine.
            assertThat(instructions)
                    .contains("adduser")
                    .contains("USER ecomdemo");
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
        void compose_always_givesEveryServiceAMemoryLimit() {
            // THEN the percentage above has something to be a percentage of, in all five. Without a
            // limit a JVM sizes its heap against the whole host - and there are six of them now, so
            // one unconstrained service is enough to push the rest into GC thrash. Phase 17 measured
            // exactly that with four.
            for (String service : SERVICES) {
                assertThat(service(service).toString())
                        .as("%s must have a memory limit", service)
                        .contains("memory");
            }
        }
    }

    @Nested
    class TheStackStartsInTheRightOrder {

        @Test
        @SuppressWarnings("unchecked")
        void compose_always_waitsForPostgresToBeHealthyNotMerelyStarted() {
            // THEN every service waits for the database to answer, not just to exist. service_started
            // is the default and is what makes people add sleeps to entrypoints.
            for (String service : SERVICES) {
                Map<String, Object> dependsOn = (Map<String, Object>) service(service).get("depends_on");
                assertThat((Map<String, Object>) dependsOn.get("postgres"))
                        .as("%s must wait for postgres to be healthy", service)
                        .containsEntry("condition", "service_healthy");
            }
        }

        @Test
        void compose_always_healthchecksEverything() {
            assertThat(service("postgres")).containsKey("healthcheck");
            for (String service : SERVICES) {
                assertThat(service(service))
                        .as("%s must have a healthcheck, or depends_on: service_healthy on it means nothing", service)
                        .containsKey("healthcheck");
            }
        }

        @Test
        @SuppressWarnings("unchecked")
        void compose_always_probesReadinessRatherThanLiveness() {
            // GIVEN order-service's healthcheck command - the most dependency-laden of the five
            Map<String, Object> healthcheck = (Map<String, Object>) service("order-service").get("healthcheck");
            String test = String.valueOf(((List<String>) healthcheck.get("test")).getLast());

            // THEN it asks the readiness group, which includes the database, so a service that is
            // running but cannot reach it is not reported healthy.
            assertThat(test).contains("/actuator/health/readiness");

            // Liveness would be the wrong probe here and is the easy mistake: it excludes
            // dependencies on purpose, so it answers UP while the database is unreachable - and
            // compose would route traffic to an instance that can serve nothing.
            assertThat(test)
                    .as("liveness excludes dependencies by design and must not be the compose probe")
                    .doesNotContain("/actuator/health/liveness");
        }
    }

    @Nested
    class NetworkingAndPersistence {

        @Test
        void compose_always_reachesPostgresByServiceNameNotLocalhost() {
            // THEN the host is the service name, in all five. Each container has its own network
            // namespace, so localhost inside a service's container is that container - there is no
            // database there.
            for (String service : SERVICES) {
                assertThat(String.valueOf(environmentOf(service).get("POSTGRES_URL")))
                        .as("%s must reach postgres by service name", service)
                        .contains("//postgres:5432/")
                        .doesNotContain("localhost")
                        .doesNotContain("127.0.0.1");
            }
        }

        @Test
        void compose_always_givesEveryServiceItsOwnDatabase() {
            // THEN no two services share a database name.
            //
            // This is the single assertion that guards the central claim of this phase. Pointing two
            // services at the same database would work perfectly, pass every other test, and quietly
            // restore the shared schema the split exists to remove - and somebody would write a join
            // across it within a month.
            List<String> databases = SERVICES.stream()
                    .map(service -> String.valueOf(environmentOf(service).get("POSTGRES_URL")))
                    .map(url -> url.substring(url.lastIndexOf('/') + 1))
                    .toList();

            assertThat(databases).doesNotHaveDuplicates();
            assertThat(databases).allSatisfy(db -> assertThat(db).startsWith("ecomdemo_"));
        }

        @Test
        void compose_always_createsThoseDatabasesOnFirstStart() throws IOException {
            // GIVEN the init script postgres runs when its volume is empty
            String initScript = read("docker/postgres/init-databases.sh");

            // THEN every database a service asks for is one the script creates. A service pointing at
            // a database nobody created fails at startup with a connection error that names the
            // database and explains nothing about why it is missing.
            for (String service : SERVICES) {
                String url = String.valueOf(environmentOf(service).get("POSTGRES_URL"));
                String database = url.substring(url.lastIndexOf('/') + 1);
                String suffix = database.substring("ecomdemo_".length());
                assertThat(initScript)
                        .as("init-databases.sh must create %s for %s", database, service)
                        .contains(suffix);
            }
        }

        @Test
        void compose_always_publishesADistinctPortPerServiceAndLeaves8080Free() {
            // THEN no two services fight over a port - a collision in compose is a container that
            // exits with a one-line error most people scroll past - and 8080 stays free for the
            // gateway that arrives in Phase 21.
            List<String> published = SERVICES.stream()
                    .map(service -> ((List<String>) service(service).get("ports")).getFirst())
                    .map(mapping -> mapping.split(":")[0])
                    .toList();

            assertThat(published).doesNotHaveDuplicates();
            assertThat(published).doesNotContain("8080");
        }

        @Test
        void compose_always_pointsEveryServiceAtCustomerServiceForTheSigningKey() {
            // THEN the four validating services fetch the public key from the one issuing service.
            //
            // customer-service is deliberately absent from this list: it holds the private key and
            // builds its own decoder from it rather than making an HTTP request to itself, which
            // would be a startup-ordering problem invented for no benefit.
            for (String service : SERVICES) {
                if (service.equals("customer-service")) {
                    assertThat(environmentOf(service))
                            .as("customer-service must not fetch its own key over HTTP")
                            .doesNotContainKey("JWKS_URI");
                    continue;
                }
                assertThat(String.valueOf(environmentOf(service).get("JWKS_URI")))
                        .as("%s must validate tokens against customer-service's published key", service)
                        .isEqualTo("http://customer-service:8081/.well-known/jwks.json");
            }
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
        void compose_always_takesEveryDatabasePasswordFromTheEnvironment() {
            // THEN no service carries a literal password, and every one uses the ${VAR:?message} form
            // so compose refuses to start rather than falling back to a value nobody chose.
            for (String service : SERVICES) {
                assertThat(String.valueOf(environmentOf(service).get("POSTGRES_PASSWORD")))
                        .as("%s must take its password from the environment", service)
                        .startsWith("${POSTGRES_PASSWORD:?");
            }
        }

        @Test
        void compose_always_keepsTheSigningKeyInExactlyOneService() {
            // THEN customer-service takes the private key from the environment, and NO other service
            // is given it.
            //
            // This is the security property RS256 bought and the one a well-meaning edit would undo
            // first - adding JWT_PRIVATE_KEY to the others "so they can validate tokens" looks like a
            // fix for a startup error and hands four services the ability to mint an admin token.
            assertThat(String.valueOf(environmentOf("customer-service").get("JWT_PRIVATE_KEY")))
                    .startsWith("${JWT_PRIVATE_KEY");

            for (String service : SERVICES) {
                if (service.equals("customer-service")) {
                    continue;
                }
                assertThat(environmentOf(service))
                        .as("%s must not hold the signing key - it only needs the public half", service)
                        .doesNotContainKey("JWT_PRIVATE_KEY")
                        .doesNotContainKey("JWT_SECRET");
            }
        }

        @Test
        void compose_never_containsALiteralPrivateKeyOrPassword() throws IOException {
            // THEN nothing that looks like key material is committed. A belt-and-braces scan of the
            // whole file rather than of named variables, because the failure this guards against is
            // somebody adding a NEW variable with a value in it.
            String raw = read("compose.yaml");
            assertThat(raw)
                    .doesNotContain("BEGIN PRIVATE KEY")
                    .doesNotContain("BEGIN RSA PRIVATE KEY");
        }

        @Test
        void envExample_always_existsAndCarriesNoRealValues() throws IOException {
            // GIVEN the template a fresh clone copies
            String example = read(".env.example");

            // THEN it names what is needed and supplies nothing usable
            assertThat(example)
                    .contains("POSTGRES_PASSWORD")
                    .contains("JWT_PRIVATE_KEY")
                    .contains("change-me")
                    // The command that generates a real key, so nobody has to go looking for it
                    .contains("openssl genpkey")
                    .doesNotContain("BEGIN PRIVATE KEY");
        }

        @Test
        void dotEnv_always_staysOutOfGit() throws IOException {
            // THEN the real file is ignored. .env.example is the committed one.
            assertThat(read(".gitignore")).contains("\n.env\n");
        }
    }
}
