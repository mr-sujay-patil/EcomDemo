package com.ecomdemo.order.client;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * Builds the two clients order-service uses to talk to its neighbours.
 *
 * <h2>Where the addresses come from</h2>
 *
 * Properties, never code. In compose the base URLs are service names on the compose network
 * ({@code http://catalog-service:8082}); locally they are {@code localhost} ports. Nothing in this
 * package knows either.
 *
 * <p>There is no service discovery here and none is needed yet: compose's DNS resolves a service name
 * to a container, which is the same job at this scale. It stops being enough when there is more than
 * one instance of a service and something has to choose between them - a load balancer, or the
 * client-side discovery Spring Cloud provides.
 *
 * <h2>Timeouts, and why they are the most important lines in this file</h2>
 *
 * An in-process method call either returns or throws. A network call has a third outcome: it hangs.
 * With no read timeout, a neighbour that has stopped answering but not closed its sockets will hold
 * this service's request threads until Tomcat has none left - at which point order-service stops
 * serving <em>everything</em>, including the endpoints that never touch the slow neighbour. That is
 * how one service's problem becomes three services' outage, and a default of "wait forever" is what
 * makes it possible.
 *
 * <p>Five seconds is chosen against what a shopper will tolerate rather than what the network needs;
 * both calls should take single-digit milliseconds, so a timeout here means something is wrong, not
 * that the limit was tight.
 *
 * <p>Timeouts bound the damage, they do not prevent it. The rest - a circuit breaker that stops
 * calling a neighbour that is clearly down, a bulkhead so one slow dependency cannot consume every
 * thread - is Resilience4j, which is Phase 22.
 */
@Configuration
public class ServiceClientsConfiguration {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    public CatalogClient catalogClient(
            @Value("${ecomdemo.clients.catalog.base-url}") String baseUrl,
            ClientHttpRequestInterceptor bearerTokenPropagation) {
        return clientFor(CatalogClient.class, baseUrl, bearerTokenPropagation);
    }

    @Bean
    public InventoryClient inventoryClient(
            @Value("${ecomdemo.clients.inventory.base-url}") String baseUrl,
            ClientHttpRequestInterceptor bearerTokenPropagation) {
        return clientFor(InventoryClient.class, baseUrl, bearerTokenPropagation);
    }

    /**
     * Forwards the caller's own token to the service being called.
     *
     * <h2>Why forward the shopper's token rather than hold a service credential</h2>
     *
     * inventory-service has to authorize {@code POST /api/stock/reservations} somehow. The
     * alternatives were a shared secret, an API key, or a service account - all of which answer the
     * question "may this <em>machine</em> do this?". Forwarding the shopper's token answers "may this
     * <em>person</em> do this?", which is the question that was being asked before the split and the
     * one the audit trail wants recorded.
     *
     * <p>Two concrete benefits. Identity survives the hop, so every service in the chain logs the
     * same customer id and a request can be followed across them. And order-service is not privileged:
     * it can do exactly what its callers could do and no more, so compromising it grants an attacker
     * nothing they did not already have.
     *
     * <p>The cost, stated plainly: inventory-service's reservation endpoints are reachable by any
     * shopper holding a valid token, not only by order-service. The gateway in Phase 21 is what stops
     * external traffic reaching internal endpoints at all, and that is a better answer than a secret
     * shared between two services - which has to be distributed, rotated, and kept out of logs.
     *
     * <h2>How the token is obtained</h2>
     *
     * {@code JwtSecurityUserConverter} keeps the original {@link Jwt} as the authentication's
     * credentials precisely so it can be read back here. Note that this is the token as presented -
     * unchanged, with its original expiry - so a long-running call cannot outlive its own
     * authorization.
     *
     * <p>{@code SecurityContextHolder} is read here, in infrastructure, rather than in a service.
     * CLAUDE.md forbids the latter and this is the documented shape of the exception: an interceptor
     * is part of the security plumbing, it runs on the request thread by definition, and there is
     * nowhere else the ambient credential could be picked up. A background thread would find nothing
     * here - which is the right failure for a call made on nobody's behalf.
     */
    @Bean
    public ClientHttpRequestInterceptor bearerTokenPropagation() {
        return (request, body, execution) -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getCredentials() instanceof Jwt jwt) {
                request.getHeaders().setBearerAuth(jwt.getTokenValue());
            }
            return execution.execute(request, body);
        };
    }

    private <T> T clientFor(Class<T> clientType, String baseUrl, ClientHttpRequestInterceptor interceptor) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);

        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor(interceptor)
                .build();

        // Spring generates the implementation of the @HttpExchange interface over that RestClient.
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(clientType);
    }
}
