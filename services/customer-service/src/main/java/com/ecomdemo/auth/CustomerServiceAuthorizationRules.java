package com.ecomdemo.auth;

import com.ecomdemo.shared.security.ServiceAuthorizationRules;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/**
 * What customer-service lets through without a token.
 *
 * <p>Three endpoints, and each is public for a reason that is really the same reason: they are the
 * ones that have to work <em>before</em> the caller has anything to present.
 *
 * <ul>
 *   <li>Registration - you cannot be required to log in in order to sign up.
 *   <li>Login - you cannot be required to present a token in order to obtain one.
 *   <li>The JWK set - a service cannot authenticate in order to fetch the key it needs to validate
 *       authentication. It is a public key; publishing it is its purpose.
 * </ul>
 *
 * <p>Everything else falls through to {@code anyRequest().authenticated()} in the shared chain, so
 * {@code GET /api/customers/me} requires a token without anybody having to say so.
 */
@Configuration
public class CustomerServiceAuthorizationRules {

    @Bean
    public ServiceAuthorizationRules customerServiceRules() {
        return registry -> registry
                .requestMatchers(HttpMethod.POST, "/api/customers/register").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json").permitAll();
    }
}
