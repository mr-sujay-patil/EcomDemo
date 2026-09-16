package com.ecomdemo.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The two beans that only the service issuing tokens needs.
 *
 * <p>The filter chain itself is not here - it is identical in all five services and comes from
 * shared-kernel's {@code ResourceServerAutoConfiguration}. Nor is method security, which every
 * service enables. What is left is the half of Phase 8 that genuinely belongs to one service: the
 * thing that checks a password, and the thing that hashes one.
 *
 * <p>That is the sharpest illustration of what the split did to security. Four services can decide
 * whether you may do something without holding a single credential, because they trust a signature.
 * Only this one ever sees a password, which means only this one can leak them.
 *
 * <h2>Authentication vs authorization</h2>
 *
 * Two questions, answered in different places now. <em>Authentication</em> establishes identity and
 * happens exactly once, here, at {@code POST /api/auth/login}: the {@code UserDetailsService} finds
 * the user and the {@code PasswordEncoder} checks the password. <em>Authorization</em> then happens on
 * every subsequent request, in whichever service received it, from the token alone. Failing the first
 * is 401, failing the second is 403.
 */
@Configuration
public class AuthSecurityConfiguration {

    /**
     * Checks an email and password. From Phase 9 this has exactly one caller - the login endpoint -
     * because no other request carries credentials; they carry a token instead.
     *
     * <p>{@code CustomerDetailsService} finds the user, the {@code PasswordEncoder} compares the
     * BCrypt hash, and on success {@code TokenService} signs a token the other four services will
     * accept without ever calling back here.
     */
    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
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
