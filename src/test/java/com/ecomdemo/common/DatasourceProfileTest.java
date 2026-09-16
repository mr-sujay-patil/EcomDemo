package com.ecomdemo.common;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins down the datasource configuration introduced in Phase 4.
 *
 * <p>The behaviour added by this phase is entirely configuration, and configuration fails in ways a
 * service test cannot see: a typo in a placeholder silently becomes a literal string, and a pool
 * setting in the wrong profile is only discovered under load. These tests bind the real
 * {@code application-*.yml} files - {@link ConfigDataApplicationContextInitializer} is what makes
 * the runner read them - and assert what the container actually ends up with.
 *
 * <p>No database is contacted. HikariCP opens no connection until one is requested, so this stays a
 * fast unit-level test that needs neither PostgreSQL nor a network.
 */
class DatasourceProfileTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class));

    @Nested
    class DevProfile {

        @Test
        void devProfile_whenNoEnvironmentVariablesAreSet_fallsBackToTheLocalPostgresDefaults() {
            // GIVEN the dev profile and an environment with no POSTGRES_* variables
            // WHEN the context starts
            runner.withPropertyValues("spring.profiles.active=dev").run(context -> {
                // THEN the committed defaults apply, and they point at a local PostgreSQL
                assertThat(context).hasSingleBean(DataSource.class);
                HikariDataSource dataSource = context.getBean(HikariDataSource.class);
                assertThat(dataSource.getJdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/ecomdemo");
                assertThat(dataSource.getUsername()).isEqualTo("ecomdemo");
            });
        }

        @Test
        void devProfile_whenTheEnvironmentSuppliesCredentials_overridesTheCommittedDefaults() {
            // GIVEN the dev profile with the environment variables the README documents
            // WHEN the context starts
            runner.withPropertyValues(
                    "spring.profiles.active=dev",
                    "POSTGRES_URL=jdbc:postgresql://db.internal:6432/ecomdemo",
                    "POSTGRES_USER=someone-else",
                    "POSTGRES_PASSWORD=not-the-default").run(context -> {
                // THEN the environment wins - which is what keeps real credentials out of the repository
                HikariDataSource dataSource = context.getBean(HikariDataSource.class);
                assertThat(dataSource.getJdbcUrl()).isEqualTo("jdbc:postgresql://db.internal:6432/ecomdemo");
                assertThat(dataSource.getUsername()).isEqualTo("someone-else");
                assertThat(dataSource.getPassword()).isEqualTo("not-the-default");
            });
        }

        @Test
        void devProfile_always_configuresTheConnectionPoolExplicitlyRatherThanByDefault() {
            // GIVEN the dev profile
            // WHEN the context starts
            runner.withPropertyValues("spring.profiles.active=dev").run(context -> {
                // THEN the pool carries the sizes chosen in application-dev.yml rather than Hikari's
                // own defaults (a pool named "HikariPool-1" sized to 10 with minimumIdle == maximum).
                HikariDataSource dataSource = context.getBean(HikariDataSource.class);
                assertThat(dataSource.getPoolName()).isEqualTo("EcomDemoPool");
                assertThat(dataSource.getMaximumPoolSize()).isEqualTo(10);
                assertThat(dataSource.getMinimumIdle()).isEqualTo(2);
                assertThat(dataSource.getConnectionTimeout()).isEqualTo(30_000);
                assertThat(dataSource.getIdleTimeout()).isEqualTo(600_000);
                assertThat(dataSource.getMaxLifetime()).isEqualTo(1_800_000);
            });
        }
    }

    @Nested
    class TestProfile {

        @Test
        void testProfile_always_usesAnEmbeddedDatabaseSoTheBuildNeedsNoServer() {
            // GIVEN the profile the test suite runs under
            // WHEN the context starts
            runner.withPropertyValues("spring.profiles.active=test").run(context -> {
                // THEN it is in-memory H2, never PostgreSQL
                HikariDataSource dataSource = context.getBean(HikariDataSource.class);
                assertThat(dataSource.getJdbcUrl())
                        .startsWith("jdbc:h2:mem:")
                        .doesNotContain("postgresql://");
            });
        }

        @Test
        void testProfile_always_runsH2InPostgresCompatibilityMode() {
            // GIVEN the test profile
            // WHEN the context starts
            runner.withPropertyValues("spring.profiles.active=test").run(context -> {
                // THEN H2 emulates PostgreSQL's syntax and its lower-casing of unquoted identifiers,
                // which narrows the gap between what the tests run on and what production runs on.
                HikariDataSource dataSource = context.getBean(HikariDataSource.class);
                assertThat(dataSource.getJdbcUrl())
                        .contains("MODE=PostgreSQL")
                        .contains("DATABASE_TO_LOWER=TRUE");
            });
        }
    }
}
