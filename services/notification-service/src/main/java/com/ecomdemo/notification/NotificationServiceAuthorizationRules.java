package com.ecomdemo.notification;

import com.ecomdemo.shared.security.ServiceAuthorizationRules;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/**
 * Confirmations belong to the customer they were sent to.
 *
 * <p>The service scopes its query by the caller's id on top of this rule, so an ADMIN who somehow
 * reached the endpoint would still see only their own - the rule and the query are two independent
 * defences, and the query is the one that actually protects the data.
 *
 * <p>Note that this service enforces this itself, from a token it verified itself, even though
 * nothing but a browser ever calls it. It would have been tempting to leave authorization to the
 * gateway that arrives in Phase 21; a gateway that is the only thing enforcing authorization is a
 * gateway whose one bypassed route is a total compromise.
 */
@Configuration
public class NotificationServiceAuthorizationRules {

    @Bean
    public ServiceAuthorizationRules notificationServiceRules() {
        return registry -> registry
                .requestMatchers(HttpMethod.GET, "/api/notifications").hasRole("CUSTOMER");
    }
}
