package com.ecomdemo.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import com.ecomdemo.shared.security.Role;
import com.ecomdemo.shared.security.SecurityUser;

import org.junit.jupiter.api.Test;

import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What goes into the token.
 *
 * <p>A real {@link NimbusJwtEncoder} rather than a mock: the assertions here are about the shape of
 * the string that comes out, and a mocked encoder could only confirm that a method was called.
 *
 * <p>The encoder comes from {@link JwtKeyConfiguration} with no configured key, so it generates an
 * ephemeral RSA keypair - which also means this test exercises that fallback path rather than
 * describing it. Phase 9's version of this test built an HMAC encoder by hand; the claims it asserts
 * on are unchanged, because the algorithm changed and the contract did not.
 */
class TokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:15:30Z");

    /** An ephemeral 2048-bit RSA keypair, exactly as the service generates when JWT_PRIVATE_KEY is unset. */
    private final JwtEncoder encoder = new JwtKeyConfiguration("").jwtEncoder();

    private final TokenService tokenService = new TokenService(
            encoder, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15));

    private static SecurityUser shopper() {
        return new SecurityUser(42L, "sam@example.com", Role.CUSTOMER);
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
    void issue_always_namesRs256AndAKeyIdInTheHeader() {
        // GIVEN / WHEN
        String token = tokenService.issue(shopper());
        String header = new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]), StandardCharsets.UTF_8);

        // THEN the header says how to verify it, so a verifier does not have to guess - and which key
        // to verify it with, which is what makes rotating the keypair possible without a flag day:
        // publish both keys in the JWK set, and every token still says which one signed it.
        assertThat(header).contains("RS256").contains("kid");
    }

    @Test
    void issue_always_producesATokenTheMatchingPublicKeyAccepts() {
        // GIVEN a service whose encoder and decoder come from the same keypair - the arrangement
        // customer-service runs with
        // The system clock, not the fixed one the other tests use: this is the one assertion that
        // runs the real exp check, and a token minted in the past is correctly refused for being
        // expired rather than for being unverifiable - which would pass for the wrong reason.
        JwtKeyConfiguration keys = new JwtKeyConfiguration("");
        TokenService service = new TokenService(
                keys.jwtEncoder(), Clock.systemUTC(), Duration.ofMinutes(15));

        // WHEN a token is issued and then verified with the PUBLIC half only
        var decoded = keys.jwtDecoder().decode(service.issue(shopper()));

        // THEN it verifies. This is the property the whole phase rests on: four services that hold
        // only this public key can check a token without being able to produce one.
        assertThat(decoded.getSubject()).isEqualTo("42");
        assertThat(decoded.getClaimAsString("role")).isEqualTo("CUSTOMER");
    }

    @Test
    void issue_always_carriesTheIdentityAndRoleAsClaims() {
        // GIVEN / WHEN
        String payload = payloadOf(tokenService.issue(shopper()));

        // THEN the subject is the customer id - what every controller and query actually needs, and
        // the one thing about a user that never changes
        // The role is in there too: the authorization decision travels in the token, which is what
        // lets a request be authorized with no database read at all.
        assertThat(payload)
                .contains("\"sub\":\"42\"")
                .contains("\"email\":\"sam@example.com\"")
                .contains("\"role\":\"CUSTOMER\"");
    }

    @Test
    void issue_always_setsAnExpiryFromTheInjectedClock() {
        // GIVEN a fixed clock and a 15 minute lifetime
        // WHEN
        String payload = payloadOf(tokenService.issue(shopper()));

        // THEN exp is iat + 15 minutes, in epoch seconds
        assertThat(payload)
                .contains("\"iat\":" + NOW.getEpochSecond())
                .contains("\"exp\":" + NOW.plus(Duration.ofMinutes(15)).getEpochSecond());
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
        SecurityUser admin = new SecurityUser(1L, "admin@ecomdemo.local", Role.ADMIN);

        // WHEN / THEN the role claim is what the authorization rules will run on
        assertThat(payloadOf(tokenService.issue(admin))).contains("\"role\":\"ADMIN\"");
    }
}
