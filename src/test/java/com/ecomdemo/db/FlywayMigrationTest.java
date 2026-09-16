package com.ecomdemo.db;

import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that Flyway - not Hibernate - built the schema the tests run against.
 *
 * <p>Reaching for native queries against {@code flyway_schema_history} and {@code information_schema}
 * is unusual, and deliberate: the thing under test here is the migration mechanism itself, not any
 * Java code. Everything below would still pass if the entities were deleted.
 *
 * <p>That the context starts at all is itself the headline assertion. {@code ddl-auto: validate}
 * means Hibernate compares every {@code @Entity} against the tables Flyway created and refuses to
 * build the {@code EntityManagerFactory} if they disagree - so a migration that forgets a column
 * fails every test in this class before a single assertion runs.
 */
@DataJpaTest
@ActiveProfiles("test")
class FlywayMigrationTest {

    @Autowired
    private EntityManager entityManager;

    @SuppressWarnings("unchecked")
    private List<Object> nativeQuery(String sql) {
        return entityManager.createNativeQuery(sql).getResultList();
    }

    @Nested
    class SchemaHistory {

        @Test
        void flywaySchemaHistory_afterStartup_recordsEveryMigrationInOrder() {
            // GIVEN a database Flyway has migrated
            // WHEN the history table is read
            List<Object> versions = nativeQuery(
                    "select version from flyway_schema_history where version is not null order by installed_rank");

            // THEN every migration in db/migration is recorded, in the order it was applied
            assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
        }

        @Test
        void flywaySchemaHistory_afterStartup_marksEveryMigrationSuccessful() {
            // GIVEN a database Flyway has migrated
            // WHEN the success flags are read
            List<Object> failures = nativeQuery(
                    "select version from flyway_schema_history where success = false");

            // THEN nothing was left half-applied
            assertThat(failures).isEmpty();
        }

        @Test
        void flywaySchemaHistory_afterStartup_storesAChecksumForEachVersionedMigration() {
            // GIVEN a database Flyway has migrated
            // WHEN the checksums are read
            List<Object> checksums = nativeQuery(
                    "select checksum from flyway_schema_history where version is not null");

            // THEN each one has the checksum Flyway compares on every later start. This is what makes
            // editing an applied migration a startup failure rather than a silent divergence.
            assertThat(checksums).hasSize(8).doesNotContainNull();
        }
    }

    @Nested
    class SchemaContents {

        @Test
        void v1_always_createsEveryTableTheEntitiesNeed() {
            // GIVEN the schema V1 built
            // WHEN the table names are read
            List<Object> tables = nativeQuery(
                    "select lower(table_name) from information_schema.tables "
                            + "where table_schema = 'public' and table_name not like 'flyway%'");

            // THEN every table the entities map to is there, and nothing else
            assertThat(tables).containsExactlyInAnyOrder(
                    "products", "carts", "cart_items", "orders", "order_items", "order_audit", "users",
                    // V8, Phase 17: the consumer's record of what it sent, and of what it has
                    // already handled.
                    "notifications", "processed_events");
        }

        @Test
        void v1_always_indexesEveryForeignKeyColumn() {
            // GIVEN the schema V1 built
            // WHEN the index names are read
            List<Object> indexes = nativeQuery(
                    "select lower(index_name) from information_schema.indexes "
                            + "where table_schema = 'public'");

            // THEN the four foreign key columns are covered. PostgreSQL indexes the referenced side of
            // a foreign key automatically but never the referencing side, so without these every join
            // fetch in CartRepository and OrderRepository scans the whole table.
            assertThat(indexes).contains(
                    "idx_cart_items_cart", "idx_cart_items_product",
                    "idx_order_items_order", "idx_order_items_product");
        }

        @Test
        void v4_always_addsTheVersionColumnNotNullWithADefault() {
            // GIVEN the optimistic lock column V4 added
            // WHEN its definition is read
            List<Object> nullable = nativeQuery(
                    "select is_nullable from information_schema.columns "
                            + "where lower(table_name) = 'products' and lower(column_name) = 'version'");

            // THEN it is NOT NULL - Hibernate needs a number to compare and increment on every write.
            // The DEFAULT 0 in the migration is what let it be added NOT NULL without rewriting the
            // rows that were already there.
            assertThat(nullable).containsExactly("NO");
        }

        @Test
        void v5_always_createsTheAuditTableWithoutAForeignKeyToOrders() {
            // GIVEN the audit table V5 added
            // WHEN its foreign keys are read
            List<Object> foreignKeys = nativeQuery(
                    "select constraint_name from information_schema.table_constraints "
                            + "where lower(table_name) = 'order_audit' "
                            + "and constraint_type = 'FOREIGN KEY'");

            // THEN there are none, deliberately: a failed attempt has no order to point at, and a
            // cascade must never be able to delete the record of what happened.
            assertThat(foreignKeys).isEmpty();
        }

        @Test
        void v3_always_addsTheCategoryColumnAsNullable() {
            // GIVEN the column V3 added
            // WHEN its definition is read
            List<Object> nullable = nativeQuery(
                    "select is_nullable from information_schema.columns "
                            + "where lower(table_name) = 'products' and lower(column_name) = 'category'");

            // THEN it exists and accepts NULL - the property that let the migration ship ahead of the
            // code, and that keeps the rows V2 seeded valid without rewriting any of them.
            assertThat(nullable).containsExactly("YES");
        }
    }

    @Nested
    class SeedData {

        @Test
        void v2_always_seedsTheCatalogue() {
            // GIVEN the seed migration
            // WHEN the catalogue is read
            List<Object> names = nativeQuery("select name from products");

            // THEN the products it inserts are present. Asserted by name rather than by counting rows:
            // the test profile's H2 lives for the whole JVM, so another test may have added products
            // by the time this one runs, and a count would make this test depend on execution order.
            assertThat(names).contains(
                    "Mechanical Keyboard", "Wireless Mouse", "27\" 4K Monitor", "USB-C Hub",
                    "Noise-Cancelling Headphones", "Laptop Stand", "1080p Webcam",
                    "Desk Microphone", "1TB Portable SSD", "Cable Management Kit");
        }

        @Test
        void v6_always_seedsExactlyOneAdministrator() {
            // GIVEN the seed in V6
            // WHEN the users table is read
            List<Object> admins = nativeQuery("select email from users where role = 'ADMIN'");

            // THEN there is one, and it is the documented local account. V2's shared cart row is
            // deliberately not asserted any more: V7 dropped the carts table and rebuilt it per
            // customer, so that row no longer exists by the time the migrations finish.
            assertThat(admins).containsExactly("admin@ecomdemo.local");
        }

        @Test
        void v7_always_givesEveryCartAnOwner() {
            // GIVEN the rebuilt carts table
            // WHEN its columns are read
            List<Object> nullable = nativeQuery(
                    "select is_nullable from information_schema.columns "
                            + "where lower(table_name) = 'carts' and lower(column_name) = 'customer_id'");

            // THEN a cart cannot exist without one
            assertThat(nullable).containsExactly("NO");
        }
    }
}
