package com.ecomdemo.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may call what.
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
 * <h2>Authentication vs authorization</h2>
 *
 * Two questions the chain answers in order, and the reason there are two status codes.
 * <em>Authentication</em> establishes identity: HTTP Basic carries an email and password, the
 * {@code UserDetailsService} finds the user, the {@code PasswordEncoder} checks the password.
 * <em>Authorization</em> then decides whether that identity may do this particular thing - the rules
 * below, plus {@code @PreAuthorize} on individual methods. Failing the first is 401, failing the
 * second is 403.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ApiErrorResponder apiErrorResponder)
            throws Exception {
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

                        // You cannot be required to log in in order to sign up.
                        .requestMatchers(HttpMethod.POST, "/api/customers/register").permitAll()

                        // Everything else under /api/products changes the catalogue.
                        .requestMatchers("/api/products", "/api/products/**").hasRole("ADMIN")

                        // A cart and an order belong to a shopper. An administrator has no cart, and
                        // deliberately cannot place orders - a role is a job, not a rank.
                        .requestMatchers("/api/cart/**", "/api/orders", "/api/orders/**").hasRole("CUSTOMER")

                        // Anything not named above requires a login. Denying by default means adding
                        // an endpoint cannot accidentally publish it.
                        .anyRequest().authenticated())

                /*
                 * HTTP Basic: the credentials are sent on every request, base64-encoded - which is
                 * encoding, not encryption, and readable by anyone who can see the traffic. It is
                 * acceptable here because this is a local learning project over localhost; in the
                 * open it would require TLS without exception. Phase 9 replaces it with JWT.
                 */
                .httpBasic(basic -> basic.authenticationEntryPoint(apiErrorResponder))

                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(apiErrorResponder)
                        .accessDeniedHandler(apiErrorResponder))

                .build();
    }

    /**
     * The one place the hashing algorithm is chosen.
     *
     * <p>BCrypt is deliberately slow and salts every hash, so identical passwords produce different
     * strings and guessing is expensive per attempt rather than per billion. The work factor is
     * encoded in the hash itself, which is what lets it be raised later without invalidating existing
     * passwords: an old hash still verifies, and can be re-hashed on next login.
     *
     * <p>Returning the {@link PasswordEncoder} interface rather than the concrete class is what makes
     * that change a one-line edit. Nothing else in the application names BCrypt.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
