package com.ecomdemo.shared.autoconfigure;

import java.time.Clock;

import com.ecomdemo.shared.ApiErrorResponder;
import com.ecomdemo.shared.GlobalExceptionHandler;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

import tools.jackson.databind.ObjectMapper;

/**
 * What every EcomDemo service gets for free by depending on shared-kernel.
 *
 * <h2>Why an auto-configuration rather than a component scan</h2>
 *
 * The obvious alternative is for each service to widen its scan - {@code @ComponentScan("com.ecomdemo")}
 * - and pick these beans up as {@code @Component}s. That works and is wrong in a way worth naming: it
 * makes the library's package layout part of its contract. Move a class between packages and five
 * services break, with no compiler error, at startup.
 *
 * <p>{@code @AutoConfiguration} inverts that. This class is listed in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}, Boot reads
 * that file from every jar on the classpath, and the beans appear wherever the jar does - regardless
 * of where the application's own main class lives. It is the same mechanism {@code spring-boot-flyway}
 * and {@code spring-boot-kafka} use, and the same one whose absence made Flyway silently not run in
 * Phase 5.
 *
 * <p>Every bean here is {@code @ConditionalOnMissingBean}. Auto-configuration is applied <em>after</em>
 * a service's own {@code @Configuration}, so a service that wants a different clock or a different
 * error shape declares one and this quietly steps aside, rather than the two fighting over the bean
 * name.
 */
@AutoConfiguration
@EnableMethodSecurity
public class SharedKernelAutoConfiguration {

    /**
     * Publishes the system clock as a bean.
     *
     * <p>Calling {@code Instant.now()} inside a service hard-codes a dependency on the real clock and
     * makes "was this timestamped correctly?" untestable. Injecting a {@link Clock} lets a test
     * substitute {@code Clock.fixed(...)} without touching the service.
     *
     * <p>It matters more after the split than before. Five processes now stamp timestamps into five
     * databases, and an event's {@code placedAt} is compared against rows written by a different
     * service on a different host - so "whose clock?" stops being rhetorical. Everything here is
     * {@code systemUTC()}, and nothing in this phase depends on the five agreeing to better than a
     * second or so; anything that did would need a logical clock rather than a wall clock.
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Translates exceptions into the single {@code {status, message}} shape, in every service.
     *
     * <p>This is the clearest argument for the module existing at all. Five services that each
     * invented their own error JSON would make a client parse five formats, and the format would
     * drift the first time somebody fixed a bug in one of them.
     */
    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    /** The same shape for 401 and 403, which are decided in the filter chain before any controller. */
    @Bean
    @ConditionalOnMissingBean
    public ApiErrorResponder apiErrorResponder(ObjectMapper objectMapper) {
        return new ApiErrorResponder(objectMapper);
    }

    /**
     * Registry-wide settings that apply to every meter in every service.
     *
     * <p>Declaring a {@code MeterRegistry} bean directly would make Boot's auto-configuration back off
     * and take the Prometheus registry, the JVM binders and the HTTP instrumentation with it. A
     * {@link MeterRegistryCustomizer} is applied <em>to</em> whatever registry Boot built, so all of
     * that survives.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    public static class CommonMetricTags {

        /**
         * Stamps every metric with the name of the service that produced it.
         *
         * <p>Phase 15 added this line while there was one application, and described the tag as
         * something that would matter "the moment a second service scrapes into the same Prometheus".
         * That moment is this phase. There are now five scrape targets, and without
         * {@code application="catalog-service"} the {@code jvm_memory_used_bytes} series from five
         * JVMs are one indistinguishable line on the dashboard.
         */
        @Bean
        @ConditionalOnMissingBean(name = "commonTags")
        public MeterRegistryCustomizer<MeterRegistry> commonTags(
                @Value("${spring.application.name}") String applicationName) {
            return registry -> registry.config().commonTags("application", applicationName);
        }
    }
}
