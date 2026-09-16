package com.ecomdemo.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;

/**
 * Switches on the proxying behind {@code @Retryable}.
 *
 * <p>Spring Framework 7 ships retry support in {@code org.springframework.resilience}, so no
 * dependency is needed - but like {@code @Transactional} before it, the annotation does nothing at
 * all until something enables the post-processor that reads it. Spring Boot auto-configures the
 * transaction one; this one has to be asked for.
 *
 * <p>Worth knowing: a method annotated {@code @Retryable} in an application without this class
 * compiles, runs, and simply never retries. There is no warning.
 */
@Configuration
@EnableResilientMethods
public class ResilienceConfiguration {
}
