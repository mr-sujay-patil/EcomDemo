package com.ecomdemo.common;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registry-wide settings that apply to every meter in the application.
 *
 * <h2>Why a customizer rather than a MeterRegistry bean</h2>
 *
 * Declaring a {@code MeterRegistry} bean directly would make Boot's auto-configuration back off and
 * take the Prometheus registry, the JVM binders and the HTTP instrumentation with it - the same trap
 * Phase 13 met with {@code CacheManager}. A {@link MeterRegistryCustomizer} is applied <em>to</em>
 * whatever registry Boot built, so all of that survives.
 */
@Configuration
public class MetricsConfiguration {

    /**
     * Stamps every metric with the name of the application that produced it.
     *
     * <p>A common tag is added once here and appears on every series, including the ones Micrometer
     * registers itself - JVM memory, Hikari pool, HTTP requests. The moment a second service scrapes
     * into the same Prometheus, {@code jvm_memory_used_bytes} from two applications is one
     * indistinguishable series without it; with it, {@code application="ecomdemo"} separates them and
     * the Grafana dashboard can be written once and filtered per service.
     *
     * <p>Adding it here rather than through {@code management.metrics.tags.*} keeps it next to this
     * explanation, and lets it take the value the application already knows itself by.
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTags(
            @Value("${spring.application.name}") String applicationName) {
        return registry -> registry.config().commonTags("application", applicationName);
    }
}
