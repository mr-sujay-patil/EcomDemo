package com.ecomdemo.shared.testsupport;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.ecomdemo.shared.security.Role;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * A stand-in for customer-service, for the four services that cannot call it in a test.
 *
 * <h2>Why this has to exist</h2>
 *
 * Before the split, an integration test got a token by registering a user and logging in, because the
 * endpoints that do those things were in the same application. After it, catalog-service has no
 * {@code /api/auth/login} and no users table - it only knows how to <em>verify</em> a token. Booting
 * customer-service alongside it just to obtain one would turn every service's test suite into a
 * multi-service deployment, which is slow, flaky, and tests the wrong thing.
 *
 * <p>So the test mints its own. One RSA keypair is generated per JVM; {@link #issue} signs tokens
 * with the private half and {@link #decoder()} verifies them with the public half. A test wires that
 * decoder into the service under test, exactly where Boot would have put one built from
 * customer-service's JWK set.
 *
 * <h2>What this proves, and what it does not</h2>
 *
 * It proves the real thing: that a service accepts a correctly signed token, rejects a badly signed
 * one, and derives the right principal and authorities from the claims - with no help from, and no
 * call to, whoever issued it. That independence is the property the phase is about, and it is
 * testable precisely <em>because</em> nothing here comes from customer-service.
 *
 * <p>It does not prove that customer-service and this class agree on the claims. Nothing in a single
 * service's test suite can: that is a contract between two deployables, and it is checked by the
 * claim-shape tests on both sides and by the end-to-end run through Compose.
 */
public final class TestTokens {

    private static final RSAKey KEY = generate();
    private static final JwtEncoder ENCODER = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(KEY)));

    private TestTokens() {
    }

    /**
     * A token that looks exactly like one customer-service would issue - the same claims, in the same
     * shape, because the authorization rules in four services depend on them.
     */
    public static String issue(long customerId, String email, Role role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("ecomdemo")
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(15)))
                .subject(String.valueOf(customerId))
                .claim("email", email)
                .claim("role", role.name())
                .build();

        return ENCODER.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }

    public static String issueCustomer(long customerId) {
        return issue(customerId, "customer-" + customerId + "@ecomdemo.local", Role.CUSTOMER);
    }

    public static String issueAdmin() {
        return issue(1L, "admin@ecomdemo.local", Role.ADMIN);
    }

    /**
     * Verifies with the public half only - the same asymmetry the running system has. A test that
     * used the private key to verify would still pass while proving nothing about whether the
     * service can tell a real token from a forged one.
     */
    public static JwtDecoder decoder() {
        try {
            return NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
        } catch (Exception ex) {
            throw new IllegalStateException("Could not build a test JwtDecoder", ex);
        }
    }

    private static RSAKey generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (Exception ex) {
            throw new IllegalStateException("Could not generate a test RSA keypair", ex);
        }
    }
}
