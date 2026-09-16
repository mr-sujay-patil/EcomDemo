package com.ecomdemo.metrics;

import com.ecomdemo.support.AbstractPostgresIT;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What Actuator exposes, to whom, and - just as importantly - what it does not.
 *
 * <p>Asserting only the endpoints that <em>are</em> exposed would pass equally happily with
 * everything exposed, which is the configuration mistake worth catching: {@code exposure.include: "*"}
 * is one character from correct and publishes {@code /actuator/env}, where the resolved configuration
 * - and therefore the connection strings - can be read.
 */
class ActuatorEndpointsIT extends AbstractPostgresIT {

    @Test
    void health_forAnyone_reportsUpWithoutNamingItsComponents() {
        // GIVEN no credentials at all
        // WHEN
        String body = anonymous.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        // THEN a probe can read the status...
        assertThat(body).contains("\"status\":\"UP\"");

        // ...and nothing else. show-details is `when-authorized`, so the breakdown - which names the
        // database and Redis and reports whether they are struggling - stays behind a login.
        assertThat(body).doesNotContain("components").doesNotContain("PostgreSQL").doesNotContain("redis");
    }

    @Test
    void health_forAnAdministrator_reportsEveryComponent() {
        // WHEN
        String body = admin.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        // THEN
        assertThat(body).contains("components").contains("\"db\"").contains("\"redis\"");
    }

    @Test
    void liveness_always_answersWithoutConsultingAnyDependency() {
        // GIVEN the liveness group, which deliberately contains only livenessState
        // WHEN
        String body = anonymous.get().uri("/actuator/health/liveness").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        // THEN it is UP and says nothing about the database. That is the whole design: a NO here
        // means "restart the process", and restarting cures nothing that is wrong with PostgreSQL.
        assertThat(body).contains("\"status\":\"UP\"");
        assertThat(body).doesNotContain("\"db\"");
    }

    @Test
    void readiness_always_reflectsTheDependenciesTrafficWouldNeed() {
        // WHEN
        String body = anonymous.get().uri("/actuator/health/readiness").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        // THEN UP, because this test has a real PostgreSQL and a real Redis behind it. A NO here
        // takes the instance out of the load balancer and leaves it running - the opposite response
        // to liveness, which is why the two groups have different contents.
        assertThat(body).contains("\"status\":\"UP\"");
    }

    @Test
    void prometheus_forAnyone_servesTheExpositionFormat() {
        // GIVEN a scraper, which cannot hold a 15-minute JWT
        // WHEN
        String body = anonymous.get().uri("/actuator/prometheus").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        // THEN the JVM and HTTP binders are publishing...
        assertThat(body).contains("jvm_memory_used_bytes").contains("http_server_requests_seconds");

        // ...every series carries the common tag from MetricsConfiguration, so a second application
        // scraped into the same Prometheus stays distinguishable...
        assertThat(body).contains("application=\"ecomdemo\"");

        // ...and the format is Prometheus's own, with HELP and TYPE lines rather than JSON.
        assertThat(body).contains("# HELP").contains("# TYPE");
    }

    @Test
    void metrics_forAnyoneWithoutAdmin_isRefused() {
        // GIVEN /actuator/metrics is the browsable JSON view, read by humans who can hold a token
        // THEN anonymous is challenged...
        anonymous.get().uri("/actuator/metrics").exchange().expectStatus().isUnauthorized();

        // ...a logged-in shopper is identified and refused - 403, not 404: unlike another customer's
        // order, the existence of this endpoint is not a secret worth keeping...
        client.get().uri("/actuator/metrics").exchange().expectStatus().isForbidden();

        // ...and an administrator gets it.
        admin.get().uri("/actuator/metrics").exchange().expectStatus().isOk();
    }

    @Test
    void metrics_forAnAdministrator_listsTheBusinessMetersThisPhaseAdded() {
        // WHEN
        String body = admin.get().uri("/actuator/metrics").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        // THEN the three meters are registered at startup, before any order has been placed -
        // because OrderMetrics resolves them in its constructor rather than on first use. A meter
        // that only appears after the first event is a meter whose absence cannot be distinguished
        // from a zero.
        assertThat(body).contains("orders.placed").contains("order.value").contains("order.checkout");
    }

    @Test
    void info_forAnyone_reportsWhichBuildIsRunning() {
        // WHEN
        String body = anonymous.get().uri("/actuator/info").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        // THEN the build-info execution added to spring-boot-maven-plugin has produced something.
        // Without it this endpoint is exposed and returns {} - working, and useless.
        assertThat(body).contains("build").contains("ecomdemo");
    }

    @Test
    void everythingElse_evenForAnAdministrator_isNotExposedAtAll() {
        // GIVEN an administrator, so a 404 here means "not exposed" rather than "not allowed" -
        // asking anonymously would produce 401 and prove nothing about the exposure list.
        // THEN
        admin.get().uri("/actuator/env").exchange().expectStatus().isNotFound();
        admin.get().uri("/actuator/beans").exchange().expectStatus().isNotFound();
        admin.get().uri("/actuator/configprops").exchange().expectStatus().isNotFound();
        admin.get().uri("/actuator/loggers").exchange().expectStatus().isNotFound();
        admin.get().uri("/actuator/threaddump").exchange().expectStatus().isNotFound();
        admin.get().uri("/actuator/heapdump").exchange().expectStatus().isNotFound();
    }
}
