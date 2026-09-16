package com.ecomdemo.shared.security;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a verified token becomes.
 *
 * <p>Most of this is unremarkable and would have been true since Phase 9. The test that matters is
 * {@link #convert_always_keepsTheTokenEvenAfterCredentialsAreErased}, which pins the fix for a bug
 * that cost an afternoon and could not be found by any test in this repository at the time.
 */
class JwtSecurityUserConverterTest {

    private final JwtSecurityUserConverter converter = new JwtSecurityUserConverter();

    private static Jwt tokenFor(long customerId, String email, String role) {
        return Jwt.withTokenValue("the-token-value")
                .header("alg", "RS256")
                .header("kid", "some-key-id")
                .subject(String.valueOf(customerId))
                .claim("email", email)
                .claim("role", role)
                .issuedAt(Instant.parse("2026-09-17T10:00:00Z"))
                .expiresAt(Instant.parse("2026-09-17T10:15:00Z"))
                .build();
    }

    @Test
    void convert_always_rebuildsTheSecurityUserFromTheClaims() {
        // GIVEN a verified token
        // WHEN
        AbstractAuthenticationToken authentication = converter.convert(tokenFor(42L, "sam@x.com", "CUSTOMER"));

        // THEN controllers keep taking @AuthenticationPrincipal SecurityUser, unchanged since Phase 8.
        // How a caller proved who they are is not the business layer's concern - which is why this
        // converter exists at all rather than letting raw claims reach a controller.
        SecurityUser principal = (SecurityUser) authentication.getPrincipal();
        assertThat(principal.getId()).isEqualTo(42L);
        assertThat(principal.getUsername()).isEqualTo("sam@x.com");
        assertThat(principal.getRole()).isEqualTo(Role.CUSTOMER);
    }

    @Test
    void convert_always_derivesAuthoritiesFromTheRoleRatherThanFromTheToken() {
        // GIVEN an admin's token
        AbstractAuthenticationToken authentication = converter.convert(tokenFor(1L, "admin@x.com", "ADMIN"));

        // THEN the ROLE_ prefix is applied by SecurityUser, in one place. A token cannot grant an
        // authority the application would never otherwise mint, because the authority is not taken
        // from the token - only the role name is.
        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void convert_always_keepsTheTokenEvenAfterCredentialsAreErased() {
        // GIVEN a converted token
        AbstractAuthenticationToken authentication = converter.convert(tokenFor(42L, "sam@x.com", "CUSTOMER"));
        assertThat(authentication.getCredentials()).isInstanceOf(Jwt.class);

        // WHEN Spring does what it does to every successful authentication.
        //
        // ProviderManager calls eraseCredentials() with eraseCredentialsAfterAuthentication = true by
        // default, so this happens on every single request, immediately after the converter runs.
        authentication.eraseCredentials();

        // THEN the token survives.
        //
        // This is the assertion the system depends on. order-service forwards the caller's own token
        // to catalog-service and inventory-service by reading it back from here; with
        // UsernamePasswordAuthenticationToken - which nulls its credentials field in
        // eraseCredentials() - it read null, sent no Authorization header, and inventory-service
        // answered 401 on every checkout.
        //
        // The symptom was "reserving stock is broken" and the cause was a security feature doing its
        // job on the wrong kind of secret: erasing a password protects the user, erasing a bearer
        // token the client already holds protects nobody and breaks delegation.
        assertThat(authentication.getCredentials())
                .as("a bearer token must survive eraseCredentials, or identity cannot be propagated")
                .isInstanceOf(Jwt.class);
        assertThat(((Jwt) authentication.getCredentials()).getTokenValue()).isEqualTo("the-token-value");
    }

    @Test
    void convert_always_marksTheAuthenticationAsAuthenticated() {
        // GIVEN / WHEN
        AbstractAuthenticationToken authentication = converter.convert(tokenFor(42L, "sam@x.com", "CUSTOMER"));

        // THEN - the signature was verified before this object was constructed, so there is nothing
        // left to check. An Authentication that is not authenticated is rejected by every rule.
        assertThat(authentication.isAuthenticated()).isTrue();
    }
}
