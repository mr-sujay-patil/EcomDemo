package com.ecomdemo.common;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the system clock as a bean.
 *
 * <p>Calling {@code Instant.now()} inside a service hard-codes a dependency on the real clock and
 * makes "was this timestamped correctly?" untestable. Injecting a {@link Clock} lets a future test
 * substitute {@code Clock.fixed(...)} without touching the service.
 */
@Configuration
public class ClockConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
