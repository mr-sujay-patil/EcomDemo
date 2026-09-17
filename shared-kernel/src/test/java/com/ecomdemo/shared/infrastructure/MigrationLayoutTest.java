package com.ecomdemo.shared.infrastructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every service owns its own migration timeline, and this is what says so.
 *
 * <h2>What replaced what</h2>
 *
 * The monolith had {@code FlywayMigrationTest}: it started a context, let Flyway run, and asserted
 * that {@code flyway_schema_history} held versions 1..8 and that the expected tables existed. That
 * test could not survive the split - there are five histories now, and each service's integration
 * suite already runs its own migrations against real PostgreSQL on every build, which is a stronger
 * check than counting rows.
 *
 * <p>What no service's own suite can check is the property the split introduced: that the five
 * timelines are genuinely separate and each starts at V1. That is what this class is for, and it
 * needs no database - it reads the files.
 *
 * <h2>Why numbering from V1 matters</h2>
 *
 * A service's migration history is a description of how <em>its</em> database got to be the way it
 * is. customer-service's database has never held a products table, so numbering its first migration
 * V6 - as it was in the monolith - would describe a past that did not happen, and would leave five
 * gaps in every service's history for migrations that belong to other services.
 *
 * <p>Renumbering is normally forbidden outright (rule 2 in CLAUDE.md): Flyway stores a checksum of
 * every file it has applied and refuses to start when one changes. It was legitimate exactly once,
 * here, because these are new and empty databases that have never run the originals. This test is
 * the record of that one-time exception having been taken consistently.
 */
class MigrationLayoutTest {

    /** {@code V<n>__snake_case_description.sql} - the naming rule Flyway enforces and CLAUDE.md states. */
    private static final Pattern MIGRATION = Pattern.compile("^V(\\d+)__[a-z0-9_]+\\.sql$");

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

    private static Path migrationsOf(String service) {
        return repositoryRoot()
                .resolve("services").resolve(service)
                .resolve("src/main/resources/db/migration");
    }

    private static List<String> migrationFileNames(String service) throws IOException {
        try (Stream<Path> files = Files.list(migrationsOf(service))) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void everyService_always_hasItsOwnMigrations() throws IOException {
        // THEN all five have a migration directory with something in it. A service with no
        // migrations and ddl-auto: validate does not start at all, so this fails fast and locally
        // rather than in a container.
        for (String service : ContainerConfigurationTest.SERVICES) {
            assertThat(migrationFileNames(service))
                    .as("%s must own its schema", service)
                    .isNotEmpty();
        }
    }

    @Test
    void everyServicesMigrations_always_startAtV1AndAreContiguous() throws IOException {
        for (String service : ContainerConfigurationTest.SERVICES) {
            List<Integer> versions = migrationFileNames(service).stream()
                    .map(MIGRATION::matcher)
                    .peek(matcher -> assertThat(matcher.matches())
                            .as("every migration must be named V<n>__snake_case.sql")
                            .isTrue())
                    .map(matcher -> Integer.parseInt(matcher.group(1)))
                    .sorted()
                    .toList();

            // THEN 1, 2, 3... with no gaps. A gap means a migration was moved to another service and
            // its number left behind, which is harmless to Flyway and misleading to every reader who
            // comes looking for V3.
            assertThat(versions.getFirst())
                    .as("%s's first migration must be V1 - its database has no earlier history", service)
                    .isEqualTo(1);

            for (int i = 0; i < versions.size(); i++) {
                assertThat(versions.get(i))
                        .as("%s's migrations must be contiguous; found %s", service, versions)
                        .isEqualTo(i + 1);
            }
        }
    }

    @Test
    void noTwoServices_ever_declareTheSameTable() throws IOException {
        // GIVEN every CREATE TABLE across all five services
        Pattern createTable = Pattern.compile("CREATE TABLE (?:IF NOT EXISTS )?([a-z_]+)");

        record OwnedTable(String service, String table) {
        }

        List<OwnedTable> owned = new java.util.ArrayList<>();
        for (String service : ContainerConfigurationTest.SERVICES) {
            for (String fileName : migrationFileNames(service)) {
                Matcher matcher = createTable.matcher(Files.readString(migrationsOf(service).resolve(fileName)));
                while (matcher.find()) {
                    owned.add(new OwnedTable(service, matcher.group(1)));
                }
            }
        }

        // THEN each table is created by exactly one service.
        //
        // Two services creating a `products` table would be two services owning the same concept,
        // which is the failure database-per-service is supposed to make obvious and which nothing
        // else in the build would notice - each service's own migrations would run perfectly against
        // its own database.
        assertThat(owned).extracting(OwnedTable::table).doesNotHaveDuplicates();

        // AND the tables that moved are where they should be, which is the split stated as data.
        assertThat(owned).contains(
                new OwnedTable("customer-service", "users"),
                new OwnedTable("catalog-service", "products"),
                new OwnedTable("inventory-service", "stock_levels"),
                new OwnedTable("order-service", "carts"),
                new OwnedTable("order-service", "orders"),
                new OwnedTable("notification-service", "notifications"));
    }

    @Test
    void noMigration_ever_referencesATableInAnotherService() throws IOException {
        // GIVEN the tables each service owns, and the foreign keys each service declares
        Pattern references = Pattern.compile("REFERENCES ([a-z_]+)");

        for (String service : ContainerConfigurationTest.SERVICES) {
            List<String> ownTables = new java.util.ArrayList<>();
            List<String> referenced = new java.util.ArrayList<>();

            for (String fileName : migrationFileNames(service)) {
                String sql = Files.readString(migrationsOf(service).resolve(fileName));
                Matcher creates = Pattern.compile("CREATE TABLE (?:IF NOT EXISTS )?([a-z_]+)").matcher(sql);
                while (creates.find()) {
                    ownTables.add(creates.group(1));
                }
                Matcher refs = references.matcher(sql);
                while (refs.find()) {
                    referenced.add(refs.group(1));
                }
            }

            // THEN every foreign key points at a table in the same database.
            //
            // A REFERENCES clause naming another service's table does not fail this build - it fails
            // at startup, in a container, with a Flyway error about a relation that does not exist.
            // More to the point, it would mean somebody had tried to reimpose a constraint the split
            // deliberately gave up, and the answer is not to add the table back but to accept that
            // the check has to live in code.
            assertThat(referenced)
                    .as("%s may only reference tables in its own database", service)
                    .isSubsetOf(ownTables);
        }
    }
}
