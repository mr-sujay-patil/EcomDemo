package com.ecomdemo.common;

import com.ecomdemo.auth.JwtSecurityUserConverter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may call what, over HTTP.
 *
 * <h2>The filter chain</h2>
 *
 * Spring Security is a chain of servlet filters that runs <em>before</em> the dispatcher servlet, so
 * before any controller, {@code @Valid} check or {@code @RestControllerAdvice}. Each filter handles
 * one concern in order - work out who the caller is, then work out whether they may proceed - and any
 * of them can end the request there and then. That ordering is why 401 and 403 need
 * {@link ApiErrorResponder} rather than the exception handler: at the point they are decided, no
 * controller has been reached and none ever will be.
 *
 * <h2>Why this is a separate class</h2>
 *
 * {@link HttpSecurity} and {@link EnableWebSecurity} only make sense in a servlet web application.
 * Left in the main security configuration, any {@code @SpringBootTest(webEnvironment = NONE)} - a
 * test wanting the services and the database but no server - would fail to start its context at all,
 * complaining about a missing bean that has nothing to do with what it was testing.
 *
 * <p>{@code @EnableWebSecurity} also registers the argument resolver behind
 * {@code @AuthenticationPrincipal}. Without it Spring MVC does not recognise the annotation, falls
 * back to treating the parameter as a model attribute, and tries to <em>construct</em> a
 * {@code SecurityUser} by data binding - which fails with a NullPointerException that says nothing
 * about security.
 */
@Configuration
@EnableWebSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebSecurityConfiguration {

    /**
     * The rules. {@link HttpSecurity} is a web-context bean, so without this
     * guard any {@code @SpringBootTest(webEnvironment = NONE)} - a test that wants the services and
     * the database but no server - fails to start the context at all, complaining about a missing
     * bean that has nothing to do with what it was testing. Spring Boot's own security
     * auto-configuration carries the same condition for the same reason.
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           ApiErrorResponder apiErrorResponder,
                                           JwtSecurityUserConverter jwtSecurityUserConverter) {
        return http
                /*
                 * CSRF protection off, because this API is stateless.
                 *
                 * The attack it defends against needs a credential the browser attaches automatically
                 * - a session cookie. A malicious page can then make the victim's browser POST to this
                 * API and the cookie rides along. A CSRF token defeats that, because the attacker
                 * cannot read it.
                 *
                 * Nothing here is attached automatically. Credentials arrive in an Authorization
                 * header that a client sets deliberately, and no session cookie is ever issued, so
                 * there is no ambient authority to abuse. Keeping CSRF on would break every non-browser
                 * client to defend against an attack that cannot happen.
                 *
                 * This reasoning is exactly as strong as "stateless", which is why the line below
                 * matters as much as this one. Store a token in a cookie later and CSRF comes back.
                 */
                .csrf(csrf -> csrf.disable())

                // No HttpSession, ever: each request authenticates itself and the server keeps nothing.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        // Browsing the catalogue needs no account. This must come before the ADMIN
                        // rule below - the first matching rule wins, so a general rule placed first
                        // would swallow the specific one.
                        .requestMatchers(HttpMethod.GET, "/api/products", "/api/products/**").permitAll()

                        // You cannot be required to log in in order to sign up, and you cannot be
                        // required to present a token in order to obtain one.
                        .requestMatchers(HttpMethod.POST, "/api/customers/register").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()

                        // Everything else under /api/products changes the catalogue.
                        .requestMatchers("/api/products", "/api/products/**").hasRole("ADMIN")

                        // A cart and an order belong to a shopper. An administrator has no cart, and
                        // deliberately cannot place orders - a role is a job, not a rank.
                        .requestMatchers("/api/cart/**", "/api/orders", "/api/orders/**").hasRole("CUSTOMER")

                        /*
                         * Actuator. Rules are matched in order, so the anonymous ones come first.
                         *
                         * health and info must be anonymous: a Docker healthcheck and - from Phase 25
                         * - a Kubernetes probe have no way to obtain a token, and a probe that cannot
                         * authenticate is a probe that always fails. show-details is `when-authorized`,
                         * so an anonymous caller sees {"status":"UP"} and nothing about the components.
                         *
                         * prometheus must be anonymous for a duller reason: Prometheus scrapes on a
                         * timer, forever, and the only credential this API issues expires in fifteen
                         * minutes with no refresh flow. There is no way for a scraper to hold one.
                         *
                         * That is a real exposure - the scrape output lists every URI template and its
                         * error counts. It is acceptable here because the whole stack is one compose
                         * network; the production answer is management.server.port on a port that is
                         * never published, which splits the Spring context in two and is a phase's work
                         * in its own right. Recorded in docs/decisions.md rather than half-done.
                         */
                        .requestMatchers(HttpMethod.GET,
                                "/actuator/health", "/actuator/health/**",
                                "/actuator/info", "/actuator/prometheus").permitAll()

                        // The browsable JSON view of the same data. A human reads this one, and a
                        // human can hold a token - so this one is not given away.
                        .requestMatchers("/actuator", "/actuator/**").hasRole("ADMIN")

                        // Anything not named above requires a login. Denying by default means adding
                        // an endpoint cannot accidentally publish it.
                        .anyRequest().authenticated())

                /*
                 * Bearer tokens instead of HTTP Basic.
                 *
                 * The BearerTokenAuthenticationFilter reads the Authorization header, hands the token
                 * to the JwtDecoder - which recomputes the signature and checks exp - and, if it
                 * holds up, converts the claims into an Authentication. A token that has been edited
                 * fails the signature check; one that is past its expiry fails the time check; either
                 * way the request never reaches a controller.
                 *
                 * The difference from Basic is what is NOT here: no password is transmitted after
                 * login, and nothing is looked up. Verification is a hash computation against a key
                 * the process already holds.
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
