package com.ecomdemo.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import javax.crypto.spec.SecretKeySpec;

import com.ecomdemo.customer.Customer;
import com.ecomdemo.customer.SecurityUser;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

import org.junit.jupiter.api.Test;

import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What goes into the token.
 *
 * <p>A real {@link NimbusJwtEncoder} rather than a mock: the assertions here are about the shape of
 * the string that comes out, and a mocked encoder could only confirm that a method was called.
 */
class TokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:15:30Z");
    private static final String KEY = "a-test-signing-key-that-is-long-enough-for-hs256";

    private final JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(
            new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));

    private final TokenService tokenService = new TokenService(
            encoder, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15));

    private static SecurityUser shopper() {
        return new SecurityUser(42L, "sam@example.com", Customer.Role.CUSTOMER);
    }

    /** Decodes the middle segment. No key needed, which is the point being demonstrated. */
    private static String payloadOf(String token) {
        return new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    @Test
    void issue_always_producesThreeDotSeparatedSegments() {
        // GIVEN a shopper
        // WHEN a token is issued
        String token = tokenService.issue(shopper());

        // THEN header.payload.signature
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    void issue_always_namesHs256InTheHeader() {
        // GIVEN / WHEN
        String token = tokenService.issue(shopper());
        String header = new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]), StandardCharsets.UTF_8);

        // THEN the header says how to verify it - which is why a verifier does not have to guess
        assertThat(header).contains("HS256");
    }

    @Test
    void issue_always_carriesTheIdentityAndRoleAsClaims() {
        // GIVEN / WHEN
        String payload = payloadOf(tokenService.issue(shopper()));

        // THEN the subject is the customer id - what every controller and query actually needs, and
        // the one thing about a user that never changes
        assertThat(payload).contains("\"sub\":\"42\"");
        assertThat(payload).contains("\"email\":\"sam@example.com\"");

        // AND the authorization decision travels in the token, which is what lets a request be
        // authorized with no database read at all
        assertThat(payload).contains("\"role\":\"CUSTOMER\"");
    }

    @Test
    void issue_always_setsAnExpiryFromTheInjectedClock() {
        // GIVEN a fixed clock and a 15 minute lifetime
        // WHEN
        String payload = payloadOf(tokenService.issue(shopper()));

        // THEN exp is iat + 15 minutes, in epoch seconds
        assertThat(payload).contains("\"iat\":" + NOW.getEpochSecond());
        assertThat(payload).contains("\"exp\":" + NOW.plus(Duration.ofMinutes(15)).getEpochSecond());
    }

    @Test
    void issue_always_leavesThePayloadReadableWithoutTheKey() {
        // GIVEN a token
        String token = tokenService.issue(shopper());

        // WHEN it is decoded by anyone at all - no key, no verification
        String payload = payloadOf(token);

        // THEN every claim is legible. A JWT is signed, not encrypted: the signature makes it
        // tamper-evident, not private. Nothing may go in here that the bearer should not read.
        assertThat(payload).contains("sam@example.com").contains("CUSTOMER");
    }

    @Test
    void issue_forAnAdministrator_carriesTheAdminRole() {
        // GIVEN an administrator
        SecurityUser admin = new SecurityUser(1L, "admin@ecomdemo.local", Customer.Role.ADMIN);

        // WHEN / THEN the role claim is what the authorization rules will run on
        assertThat(payloadOf(tokenService.issue(admin))).contains("\"role\":\"ADMIN\"");
    }
}
