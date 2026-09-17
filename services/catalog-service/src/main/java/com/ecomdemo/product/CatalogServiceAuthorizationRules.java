package com.ecomdemo.product;

import com.ecomdemo.shared.security.ServiceAuthorizationRules;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/**
 * Who may read and who may change the catalogue.
 *
 * <p>The rules are exactly the ones the monolith had, moved here with their reasoning intact:
 * browsing needs no account, and everything else under {@code /api/products} needs ADMIN. Order
 * matters - the first matching rule wins, so the permissive GET rule has to come before the
 * catch-all ADMIN rule or the latter would swallow it.
 *
 * <p>What is worth noticing is that this service enforces them <em>itself</em>, from a token it
 * verified itself, with no call to customer-service. An administrator's ADMIN role travelled in the
 * signature; catalog-service checks the signature against a public key it fetched once and decides
 * locally. Phase 21 will put a gateway in front of this, and these rules should stay exactly where
 * they are when it does: a gateway that is the only thing enforcing authorization is a gateway whose
 * one bypassed route is a total compromise.
 */
@Configuration
public class CatalogServiceAuthorizationRules {

    @Bean
    public ServiceAuthorizationRules catalogServiceRules() {
        return registry -> registry
                // Browsing the catalogue needs no account. This must come before the ADMIN rule
                // below - a general rule placed first would swallow the specific one.
                .requestMatchers(HttpMethod.GET, "/api/products", "/api/products/**").permitAll()

                // Everything else under /api/products changes the catalogue.
                .requestMatchers("/api/products", "/api/products/**").hasRole("ADMIN");
    }
}
