package com.ecomdemo.metrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

// Jackson 3 under Spring Boot 4: the base package is tools.jackson, not com.fasterxml.jackson.
// Every example written before Boot 4 shows the old one, and the only symptom is "package does not
// exist" on an import that looks entirely correct.
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the monitoring stack's configuration, in the spirit of {@code ContainerConfigurationTest}.
 *
 * <p>Every failure this class exists to catch is silent. A dashboard panel querying a metric that no
 * longer exists draws a flat line; a rules file nobody references is valid YAML that never runs; a
 * scrape target pointing at {@code localhost} resolves inside the Prometheus container and simply
 * finds nothing. None of it throws, none of it logs, and {@code docker compose up} is green
 * throughout - which is precisely why it is worth asserting.
 */
class MonitoringConfigurationTest {

    private static Map<String, Object> compose;
    private static Map<String, Object> prometheus;
    private static Map<String, Object> alertRules;
    private static Map<String, Object> datasources;
    private static JsonNode dashboard;
    private static String dashboardJson;

    /**
     * Walks up from the module directory to the repository root.
     *
     * <p>Maven runs this from services/order-service, and compose.yaml and docker/ belong to no
     * module. Hard-coding {@code ../../} would work until somebody moved the module.
     */
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

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void readTheFiles() throws IOException {
        compose = new Yaml().load(Files.readString(repositoryRoot().resolve("compose.yaml")));
        prometheus = new Yaml().load(Files.readString(repositoryRoot().resolve("docker/prometheus/prometheus.yml")));
        alertRules = new Yaml().load(Files.readString(repositoryRoot().resolve("docker/prometheus/alert-rules.yml")));
        datasources = new Yaml().load(
                Files.readString(repositoryRoot().resolve("docker/grafana/provisioning/datasources/prometheus.yml")));
        dashboardJson = Files.readString(repositoryRoot().resolve("docker/grafana/dashboards/ecomdemo.json"));
        dashboard = new ObjectMapper().readTree(dashboardJson);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> service(String name) {
        return (Map<String, Object>) ((Map<String, Object>) compose.get("services")).get(name);
    }

    @Nested
    class PrometheusScrapesTheApplication {

        @Test
        @SuppressWarnings("unchecked")
        void prometheus_always_scrapesTheAppByServiceNameNotLocalhost() {
            // GIVEN the ecomdemo scrape job
            List<Map<String, Object>> jobs = (List<Map<String, Object>>) prometheus.get("scrape_configs");
            Map<String, Object> app = jobs.stream()
                    .filter(job -> "ecomdemo".equals(job.get("job_name")))
                    .findFirst().orElseThrow();

            // WHEN its targets are read
            List<Map<String, Object>> statics = (List<Map<String, Object>>) app.get("static_configs");
            List<String> targets = (List<String>) statics.get(0).get("targets");

            // THEN all five, by service name - localhost inside the Prometheus container is
            // Prometheus, and the services would simply never be scraped. The graphs would be empty,
            // with no error anywhere.
            //
            // Five targets under one job rather than five jobs: Prometheus labels each series with
            // the instance it scraped, and shared-kernel stamps an `application` tag on every meter,
            // so they stay distinguishable either way. A missing target here is a service whose
            // dashboard panels are blank while everything appears to work.
            assertThat(targets).containsExactlyInAnyOrder(
                    "customer-service:8081",
                    "catalog-service:8082",
                    "inventory-service:8083",
                    "order-service:8084",
                    "notification-service:8085");
            assertThat(app.get("metrics_path"))
                    .as("Actuator does not serve the exposition format on Prometheus's default /metrics")
                    .isEqualTo("/actuator/prometheus");
        }

        @Test
        @SuppressWarnings("unchecked")
        void prometheus_always_loadsTheRulesFileItShipsWith() {
            // GIVEN the rule_files list
            List<String> ruleFiles = (List<String>) prometheus.get("rule_files");

            // THEN a rules file that is never referenced is the classic silent failure here: the
            // file is valid, the rule is correct, and it is never evaluated.
            assertThat(ruleFiles).isNotEmpty();
            assertThat(ruleFiles).allSatisfy(file ->
                    assertThat(repositoryRoot().resolve("docker/prometheus")
                            .resolve(Path.of(file).getFileName().toString()))
                            .as("rule_files entry %s must exist in the repository", file)
                            .exists());
        }
    }

    @Nested
    class TheAlertIsUsable {

        @Test
        @SuppressWarnings("unchecked")
        void alertRule_always_measuresServerErrorsAsAFractionAndWaitsBeforeFiring() {
            // GIVEN the single rule
            List<Map<String, Object>> groups = (List<Map<String, Object>>) alertRules.get("groups");
            List<Map<String, Object>> rules = (List<Map<String, Object>>) groups.get(0).get("rules");
            Map<String, Object> rule = rules.get(0);
            String expr = String.valueOf(rule.get("expr"));

            // THEN it rates a counter rather than reading it. A counter's raw value answers "how
            // many since this JVM started", which is never the question an alert asks.
            assertThat(expr).contains("rate(");

            // ...counts only 5xx, so a 404 or a 409 - the application working correctly - pages
            // nobody...
            assertThat(expr).contains("outcome=\"SERVER_ERROR\"");

            // ...and divides, so the threshold is a proportion rather than an absolute count that
            // would mean something different at every traffic level.
            assertThat(expr).contains("/").contains("> 0.05");

            // The most important line in the rule: without `for`, one 500 in a quiet minute is a
            // 100% error rate and an instant page.
            assertThat(rule.get("for")).as("an alert with no `for` fires on a single blip").isEqualTo("2m");
        }
    }

    @Nested
    class GrafanaIsProvisionedFromFiles {

        @Test
        @SuppressWarnings("unchecked")
        void datasource_always_carriesAnExplicitUidAndReachesPrometheusByServiceName() {
            // GIVEN the provisioned data source
            List<Map<String, Object>> sources = (List<Map<String, Object>>) datasources.get("datasources");
            Map<String, Object> source = sources.get(0);

            // THEN an unset uid is generated per install, and a provisioned dashboard then points
            // at a data source that exists on no other machine.
            assertThat(source.get("uid")).isEqualTo("ecomdemo-prometheus");
            assertThat(source.get("url")).isEqualTo("http://prometheus:9090");
            assertThat(String.valueOf(source.get("url"))).doesNotContain("localhost");
        }

        @Test
        void dashboard_always_pointsEveryPanelAtTheProvisionedDatasource() {
            // GIVEN every datasource reference anywhere in the dashboard
            List<String> uids = dashboard.findValues("datasource").stream()
                    .filter(node -> node.hasNonNull("uid"))
                    .map(node -> node.get("uid").asText())
                    .toList();

            // THEN they all resolve. A mismatch here renders as "Datasource not found" on every
            // panel, on a dashboard that provisioned without complaint.
            assertThat(uids).isNotEmpty();
            assertThat(uids).containsOnly("ecomdemo-prometheus");
        }

        @Test
        void dashboard_always_queriesMetricsThisApplicationActuallyPublishes() {
            // GIVEN every PromQL expression on the dashboard
            List<String> expressions = dashboard.findValues("expr").stream()
                    .map(JsonNode::asText)
                    .toList();
            String allExpressions = String.join("\n", expressions);

            // THEN the three business meters are on it, under the names OrderMetricsTest pins by
            // scraping them for real. Renaming a meter is a two-file change, and this is the file
            // that would otherwise be forgotten.
            assertThat(allExpressions)
                    .contains("orders_placed_total")
                    .contains("order_value_bucket")
                    .contains("order_checkout_seconds_count");

            // ...along with the RED and USE staples.
            assertThat(allExpressions)
                    .contains("http_server_requests_seconds_count")
                    .contains("http_server_requests_seconds_bucket")
                    .contains("jvm_memory_used_bytes")
                    .contains("hikaricp_connections_active");
        }

        @Test
        void dashboard_always_filtersByTheCommonTagTheApplicationStamps() {
            // GIVEN the dashboard's queries
            // THEN they scope to this application. Without the common tag from MetricsConfiguration,
            // a second service scraped into the same Prometheus would silently be added into these
            // panels - jvm_memory_used_bytes from two applications is one indistinguishable series.
            List<String> expressions = dashboard.findValues("expr").stream()
                    .map(JsonNode::asText)
                    .toList();
            assertThat(expressions).allSatisfy(expr ->
                    assertThat(expr).as("every panel query scopes to application=\"ecomdemo\"")
                            .contains("application=\"ecomdemo\""));
        }
    }

    @Nested
    class TheStackStartsInTheRightOrderAndKeepsItsSecrets {

        @Test
        @SuppressWarnings("unchecked")
        void compose_always_startsGrafanaAfterPrometheusAndPrometheusAfterTheApp() {
            // GIVEN the dependency edges
            Map<String, Object> grafanaDeps = (Map<String, Object>) service("grafana").get("depends_on");

            // THEN Grafana waits for Prometheus to be healthy, not merely started - the same lesson
            // as Phase 10's database. A Grafana that starts before Prometheus answers provisions a
            // data source it cannot reach.
            assertThat((Map<String, Object>) grafanaDeps.get("prometheus"))
                    .containsEntry("condition", "service_healthy");

            // AND Prometheus waits for nothing.
            //
            // It used to wait for `app`. Waiting for all five services would make the monitoring
            // stack unavailable exactly when it is most wanted - while something is failing to start
            // - and Prometheus is built for targets that are down: it records `up == 0` and carries
            // on, which is the signal you actually want at that moment.
            assertThat(service("prometheus"))
                    .as("prometheus must not wait for the services it monitors")
                    .doesNotContainKey("depends_on");
        }

        @Test
        @SuppressWarnings("unchecked")
        void compose_always_takesTheGrafanaPasswordFromTheEnvironmentAndRefusesADefault() {
            // GIVEN Grafana's environment
            Map<String, Object> environment = (Map<String, Object>) service("grafana").get("environment");

            // THEN the :? form, so an unset variable stops the stack rather than leaving Grafana on
            // admin/admin - the most guessed credential pair in operations.
            assertThat(String.valueOf(environment.get("GF_SECURITY_ADMIN_PASSWORD")))
                    .contains("${GRAFANA_PASSWORD:?");

            // And anonymous viewing stays off.
            assertThat(String.valueOf(environment.get("GF_AUTH_ANONYMOUS_ENABLED"))).isEqualTo("false");
        }

        @Test
        void composeAndDashboards_always_carryNoLiteralPassword() throws IOException {
            // GIVEN every file this phase added, plus compose
            List<Path> files = Stream.concat(
                            Stream.of(repositoryRoot().resolve("compose.yaml")),
                            Files.walk(repositoryRoot().resolve("docker")).filter(Files::isRegularFile))
                    .toList();

            // THEN none of them carries a committed secret.
            assertThat(files).allSatisfy(file ->
                    assertThat(Files.readString(file))
                            .as("%s must not contain a literal password", file)
                            .doesNotContain("GF_SECURITY_ADMIN_PASSWORD=")
                            .doesNotContainIgnoringCase("password: admin"));
        }

        @Test
        void envExample_always_documentsTheGrafanaPassword() throws IOException {
            // GIVEN .env.example, the file a new clone copies
            String example = Files.readString(repositoryRoot().resolve(".env.example"));

            // THEN the variable compose demands is named there, or the stack refuses to start with
            // a message about a variable nobody has heard of.
            assertThat(example).contains("GRAFANA_PASSWORD=");
            assertThat(Files.readString(repositoryRoot().resolve(".gitignore"))).contains(".env");
        }
    }
}
