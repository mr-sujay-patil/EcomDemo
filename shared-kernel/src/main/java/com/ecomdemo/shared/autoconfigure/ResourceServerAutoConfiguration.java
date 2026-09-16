package com.ecomdemo.shared.autoconfigure;

import com.ecomdemo.shared.ApiErrorResponder;
import com.ecomdemo.shared.security.JwtSecurityUserConverter;
import com.ecomdemo.shared.security.ServiceAuthorizationRules;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The filter chain every service runs, and the reason "every service validates JWTs independently"
 * is a line of configuration rather than a project.
 *
 * <h2>Independently means independently</h2>
 *
 * Each of the five services builds this chain in its own JVM and verifies every token itself. No
 * service asks customer-service "is this token good?" - that would put a synchronous call to one
 * process in front of every request to the other four, and make the whole system exactly as available
 * as its least available member. Verification is a signature check against a public key the service
 * already holds, so it costs microseconds and no network.
 *
 * <p>The public key arrives over the network exactly once, lazily, from customer-service's JWKS
 * endpoint - see {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri} in each service's
 * configuration - and is then cached. That is why RS256 replaced the HMAC secret of Phase 9: with
 * HS256 the key that <em>verifies</em> a token is the key that <em>signs</em> one, so shipping it to
 * all five services would give notification-service everything it needs to mint itself an admin
 * token. With RS256 four of the five hold only the public half and can verify without being able to
 * forge.
 *
 * <h2>Why this is separate from {@link SharedKernelAutoConfiguration}</h2>
 *
 * {@link HttpSecurity} and {@link EnableWebSecurity} only make sense in a servlet web application.
 * Left in the general configuration, any {@code @SpringBootTest(webEnvironment = NONE)} - a test
 * wanting the services and the database but no server - would fail to start its context at all,
 * complaining about a missing bean that has nothing to do with what it was testing.
 */
@AutoConfiguration
@EnableWebSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ResourceServerAutoConfiguration {

    /**
     * Rebuilds a {@code SecurityUser} from the token's claims, so controllers keep taking
     * {@code @AuthenticationPrincipal SecurityUser} rather than a bag of claims.
     */
    @Bean
    @ConditionalOnMissingBean
    public JwtSecurityUserConverter jwtSecurityUserConverter() {
        return new JwtSecurityUserConverter();
    }

    /**
     * Who may call what.
     *
     * <p>The parts that are the same everywhere are here; the parts that differ arrive as
     * {@link ServiceAuthorizationRules} beans from the service itself. Rules are applied in this
     * order, and the first match wins:
     *
     * <ol>
     *   <li>the service's own rules - the only place anything is made public;
     *   <li>the actuator rules;
     *   <li>{@code anyRequest().authenticated()}, so a path nobody named needs a token.
     * </ol>
     */
    @Bean
    @ConditionalOnMissingBean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           ApiErrorResponder apiErrorResponder,
                                           JwtSecurityUserConverter jwtSecurityUserConverter,
                                           ObjectProvider<ServiceAuthorizationRules> serviceRules) throws Exception {
        return http
                /*
                 * CSRF protection off, because this API is stateless.
                 *
                 * The attack it defends against needs a credential the browser attaches automatically
                 * - a session cookie. Nothing here is attached automatically: credentials arrive in an
                 * Authorization header that a client sets deliberately, and no session cookie is ever
                 * issued, so there is no ambient authority to abuse.
                 *
                 * This reasoning is exactly as strong as "stateless", which is why the line below
                 * matters as much as this one. Store a token in a cookie later and CSRF comes back.
                 */
                .csrf(csrf -> csrf.disable())

                // No HttpSession, ever: each request authenticates itself and the server keeps nothing.
                // Five services and no shared session store is not a coincidence - statelessness is
                // what makes "route this request to whichever instance is free" a decision nobody has
                // to think about.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> {
                    // The service's own rules first: they are the specific ones, and a general rule
                    // placed before a specific one silently swallows it.
                    serviceRules.orderedStream().forEach(rules -> rules.configure(auth));

                    /*
                     * Actuator. health and info must be anonymous: a Docker healthcheck - and from
                     * Phase 25 a Kubernetes probe - has no way to obtain a token, and a probe that
                     * cannot authenticate is a probe that always fails. show-details is
                     * `when-authorized`, so an anonymous caller sees {"status":"UP"} and nothing
                     * about the components.
                     *
                     * prometheus must be anonymous for a duller reason: Prometheus scrapes on a
                     * timer, forever, and the only credential this system issues expires in fifteen
                     * minutes with no refresh flow. There is no way for a scraper to hold one. That
                     * exposure is acceptable here because the whole stack is one compose network;
                     * the production answer is management.server.port on a port that is never
                     * published.
                     */
                    auth.requestMatchers(HttpMethod.GET,
                            "/actuator/health", "/actuator/health/**",
                            "/actuator/info", "/actuator/prometheus").permitAll();

                    // The browsable JSON view of the same data. A human reads this one, and a human
                    // can hold a token - so this one is not given away.
                    auth.requestMatchers("/actuator", "/actuator/**").hasRole("ADMIN");

                    // Anything not named above requires a login. Denying by default means adding an
                    // endpoint cannot accidentally publish it.
                    auth.anyRequest().authenticated();
                })

                /*
                 * The BearerTokenAuthenticationFilter reads the Authorization header, hands the token
                 * to the JwtDecoder - which recomputes the signature and checks exp - and, if it
                 * holds up, converts the claims into an Authentication. A token that has been edited
                 * fails the signature check; one past its expiry fails the time check; either way the
                 * request never reaches a controller.
                 *
                 * The JwtDecoder itself is auto-configured by Boot from the jwk-set-uri property.
                 * customer-service is the exception: it holds the private key, so it declares its own
                 * decoder over the matching public key rather than fetching from itself.
                 */
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(apiErrorResponder)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtSecurityUserConverter)))

                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(apiErrorResponder)
                        .accessDeniedHandler(apiErrorResponder))

                .build();
    }
}
