package com.ecomdemo.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Security that is not about HTTP: the password encoder and method security.
 *
 * <p>The rules themselves live in {@link WebSecurityConfiguration}, which only exists in a servlet
 * application. What stays here is what every context needs: the password encoder, and method
 * security, which guards service methods whether or not there is a web layer at all.
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
