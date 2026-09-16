package com.ecomdemo.order;

import com.ecomdemo.shared.security.ServiceAuthorizationRules;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A cart and an order belong to a shopper.
 *
 * <p>Nothing here is public: every endpoint in this service acts on somebody's data and there is no
 * sensible anonymous view of a cart. An administrator deliberately cannot place orders either - a
 * role is a job, not a rank - which is why the rule names CUSTOMER rather than "any authenticated
 * user".
 *
 * <p>The rule is one line, and it is only the first of two defences. {@code OrderService} also
 * carries {@code @PreAuthorize("#customerId == authentication.principal.id")}, and the repository
 * queries put the owner in the WHERE clause so another customer's row is never loaded at all. Asking
 * for somebody else's order returns 404 rather than 403, because 403 confirms it exists and turns
 * sequential ids into an enumeration tool.
 */
@Configuration
public class OrderServiceAuthorizationRules {

    @Bean
    public ServiceAuthorizationRules orderServiceRules() {
        return registry -> registry
                .requestMatchers("/api/cart/**", "/api/orders", "/api/orders/**").hasRole("CUSTOMER");
    }
}
