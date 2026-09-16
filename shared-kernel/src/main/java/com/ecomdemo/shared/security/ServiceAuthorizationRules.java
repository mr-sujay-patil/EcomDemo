package com.ecomdemo.shared.security;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * How a service says which of <em>its own</em> endpoints are public and who may call the rest.
 *
 * <p>The filter chain itself is identical in all five services - CSRF off, no session, bearer tokens,
 * the shared error shape for 401 and 403 - and lives in
 * {@link com.ecomdemo.shared.autoconfigure.ResourceServerAutoConfiguration}. Only the rules differ,
 * so only the rules are a service's business. A service contributes a bean of this type; everything
 * else it inherits.
 *
 * <p><strong>Order is the thing to be careful about.</strong> Spring Security matches rules in the
 * order they are registered and the first match wins, so a general rule placed before a specific one
 * silently swallows it. The shared chain applies every {@code ServiceAuthorizationRules} bean first,
 * then the actuator rules, then {@code anyRequest().authenticated()} - which means a service can
 * only ever open something up, never accidentally leave a gap: a path nobody named requires a token.
 *
 * <p>Deny-by-default survived the split, in other words. It is worth noticing that it now has to
 * survive it five times, and that a rule forgotten in one service is a hole in the system even if
 * the other four are right.
 */
@FunctionalInterface
public interface ServiceAuthorizationRules {

    void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry);
}
