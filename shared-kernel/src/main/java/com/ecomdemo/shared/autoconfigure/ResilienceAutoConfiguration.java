package com.ecomdemo.shared.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
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
 *
 * <p>In shared-kernel since Phase 20, because two services need it for the same reason. order-service
 * retries a checkout whose optimistic lock lost; inventory-service retries a stock reservation whose
 * optimistic lock lost - and those are now two different rows in two different databases contending
 * for two different reasons. The other three enable proxying they never use, which costs a
 * post-processor at startup and nothing at runtime; that is a better trade than three copies of this
 * file and a fourth service that silently does not retry because somebody forgot it.
 */
@AutoConfiguration
@EnableResilientMethods
public class ResilienceAutoConfiguration {
}
