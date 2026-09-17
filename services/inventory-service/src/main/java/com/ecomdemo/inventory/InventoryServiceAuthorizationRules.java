package com.ecomdemo.inventory;

import com.ecomdemo.shared.security.ServiceAuthorizationRules;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/**
 * Who may read stock, who may set it, and who may reserve it.
 *
 * <p>Reading is public, for the same reason browsing the catalogue is: a shop that made you log in to
 * see whether something was available would be a strange shop. Setting stock is ADMIN. Reserving and
 * releasing are CUSTOMER, which deserves a note.
 *
 * <h2>Why reservations are CUSTOMER and not "trusted service"</h2>
 *
 * The caller of {@code POST /api/stock/reservations} is order-service, not a browser. It could have
 * been given a service account, or an API key, or a network rule saying only it may call this. What
 * it does instead is forward the shopper's own token - so this service authorizes the human on whose
 * behalf the work is happening, and knows their id, rather than authorizing a machine.
 *
 * <p>The benefit is that identity survives the hop: every service in the chain can see who asked, and
 * a compromised order-service can do exactly what its callers could do and no more. The cost is that
 * these endpoints are reachable by any shopper with a valid token, who could reserve stock without
 * ever placing an order. In a real system the gateway (Phase 21) would stop external traffic reaching
 * them at all, which is a much better answer than a secret shared between two services.
 */
@Configuration
public class InventoryServiceAuthorizationRules {

    @Bean
    public ServiceAuthorizationRules inventoryServiceRules() {
        return registry -> registry
                // Availability is as public as the catalogue it describes. Specific rule first: a
                // general /api/stock/** rule placed before this would swallow it.
                .requestMatchers(HttpMethod.GET, "/api/stock", "/api/stock/**").permitAll()

                // Reserving and releasing happen on behalf of a shopper checking out.
                .requestMatchers(HttpMethod.POST, "/api/stock/reservations", "/api/stock/releases")
                        .hasRole("CUSTOMER")

                // Setting an absolute figure is an administrator's job.
                .requestMatchers("/api/stock", "/api/stock/**").hasRole("ADMIN");
    }
}
