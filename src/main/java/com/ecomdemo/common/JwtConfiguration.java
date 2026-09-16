package com.ecomdemo.common;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * The signing key, and the two objects that use it.
 *
 * <h2>Symmetric (HMAC) rather than asymmetric (RSA)</h2>
 *
 * HS256 signs with one secret key and verifies with the same one. That is the right fit here because
 * a single application both issues and validates its tokens - there is nobody else to tell apart.
 *
 * <p>RS256 would use a private key to sign and a public key to verify, and the difference matters as
 * soon as more than one service is involved: every service can verify a token by fetching the public
 * key, while only the issuer can mint one. With HMAC, anyone who can verify can also forge, so every
 * service that checks a token must hold the key that creates them - which is why a shared HMAC secret
 * stops scaling the moment the monolith splits (Phase 20) or an external identity provider appears.
 *
 * <h2>Where the key comes from</h2>
 *
 * {@code JWT_SECRET}, and nothing is committed. If it is absent a random key is generated at startup,
 * which keeps a fresh clone working with no setup - at the cost that tokens do not survive a restart,
 * because the key that signed them no longer exists. That is a feature for a demo: it makes vivid
 * that the key <em>is</em> the trust anchor, and that losing it invalidates every token ever issued.
 */
@Configuration
public class JwtConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JwtConfiguration.class);

    /** HS256 requires a key of at least 256 bits; a shorter one is rejected outright by Nimbus. */
    private static final int MINIMUM_KEY_BYTES = 32;

    /**
     * Held once rather than constructed per call.
     *
     * <p>A {@link SecureRandom} seeds itself from the operating system on construction, which is the
     * expensive part, and instances are thread-safe and designed to be shared. Creating a new one for
     * each use pays that cost repeatedly and, on some platforms, can block waiting for entropy.
     * Flagged by SonarQube as java:S2119.
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SecretKey signingKey;

    public JwtConfiguration(@Value("${ecomdemo.jwt.secret:}") String configuredSecret) {
        this.signingKey = configuredSecret.isBlank() ? generateEphemeralKey() : toKey(configuredSecret);
    }

    private static SecretKey toKey(String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MINIMUM_KEY_BYTES) {
            // Fail at startup with a sentence that says what to do, rather than at the first login
            // with a Nimbus error about key lengths.
            throw new IllegalStateException(
                    "JWT_SECRET must be at least " + MINIMUM_KEY_BYTES + " characters for HS256; got "
                            + bytes.length + ". Generate one with: openssl rand -base64 48");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    private static SecretKey generateEphemeralKey() {
        byte[] key = new byte[MINIMUM_KEY_BYTES];
        SECURE_RANDOM.nextBytes(key);
        log.warn("JWT_SECRET is not set - generated an ephemeral signing key. Tokens will stop "
                + "working when this application restarts, and other instances will reject them. "
                + "Set JWT_SECRET for anything beyond a single local process.");
        return new SecretKeySpec(key, "HmacSHA256");
    }

    /** Signs tokens at login. */
    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey));
    }

    /**
     * Verifies tokens on every request: recomputes the signature over the header and payload, and
     * checks {@code exp}. A token whose payload has been edited no longer matches its signature, and
     * an attacker cannot produce a new one without the key.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(signingKey).build();
    }
}
