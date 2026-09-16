package com.ecomdemo.auth;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.UUID;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

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
 * <h2>Why this changed in Phase 20</h2>
 *
 * Phase 9 signed tokens with HS256 - one secret, used both to sign and to verify - and the comment on
 * that class said what would eventually go wrong with it:
 *
 * <blockquote>With HMAC, anyone who can verify can also forge, so every service that checks a token
 * must hold the key that creates them - which is why a shared HMAC secret stops scaling the moment
 * the monolith splits (Phase 20).</blockquote>
 *
 * <p>This is that moment. There are now five services and every one of them has to verify tokens
 * independently. Shipping {@code JWT_SECRET} to all five would mean notification-service - whose
 * entire job is writing rows when an event arrives - holding everything needed to mint itself an
 * {@code ADMIN} token for any user id it liked. A compromise of the least important service would be
 * a compromise of the whole system.
 *
 * <p>RS256 splits the key in two. customer-service holds the private half and is the only thing that
 * can sign; the other four fetch the public half and can only check. That asymmetry is the entire
 * reason the public key can be published at a URL that needs no authentication - see
 * {@link JwksController}.
 *
 * <h2>Where the key comes from</h2>
 *
 * {@code ecomdemo.jwt.private-key} (from the {@code JWT_PRIVATE_KEY} environment variable), a PKCS#8
 * PEM. Nothing is committed. If it is absent a keypair is generated at startup, which keeps a fresh
 * clone working with no setup - at the cost that tokens do not survive a restart of this service,
 * because the key that signed them no longer exists.
 *
 * <p>That cost is larger than it was in Phase 9 and worth understanding. The other four services
 * cache the JWK set they fetched, so after customer-service restarts with a new key they will reject
 * tokens signed by the old one - correctly - and accept ones signed by the new key only once their
 * cache refreshes. A real deployment sets the variable, and rotates keys by publishing both in the
 * JWK set for an overlap period, which is precisely what the {@code kid} header below exists for.
 */
@Configuration
public class JwtKeyConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyConfiguration.class);

    /**
     * 2048 bits. The floor for RSA signatures anyone should accept today; 4096 costs noticeably more
     * per signature and buys margin this system does not need.
     */
    private static final int KEY_SIZE = 2048;

    private final RSAKey rsaKey;

    public JwtKeyConfiguration(@Value("${ecomdemo.jwt.private-key:}") String configuredPrivateKey) {
        this.rsaKey = configuredPrivateKey.isBlank()
                ? generateEphemeralKey()
                : fromPem(configuredPrivateKey);
    }

    /**
     * The keypair, public and private halves together.
     *
     * <p>Exposed as a bean so {@link JwksController} can publish the public half without this class
     * needing to know anything about HTTP, and so a test can sign a token with the same key the
     * application will verify it with.
     */
    @Bean
    public RSAKey rsaKey() {
        return rsaKey;
    }

    /**
     * Signs tokens at login.
     *
     * <p>The encoder is given a {@code JWKSource} rather than a bare key, which is what lets it stamp
     * the {@code kid} header onto every token it issues. A verifier then knows which of the published
     * keys to check against instead of trying each in turn - the mechanism that makes key rotation
     * possible without a flag day.
     */
    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));
    }

    /**
     * Verifies tokens on every request to <em>this</em> service.
     *
     * <p>Declared explicitly, unlike the other four services, which get theirs from Boot's
     * auto-configuration reading {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri}. This
     * service already holds the key in memory; making it issue an HTTP request to itself to discover
     * its own public key would be a startup-ordering problem invented for no benefit.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        try {
            return NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
        } catch (Exception ex) {
            throw new IllegalStateException("Could not build a JwtDecoder from the signing key", ex);
        }
    }

    /**
     * Parses a PKCS#8 PEM - the {@code -----BEGIN PRIVATE KEY-----} form produced by
     * {@code openssl genpkey -algorithm RSA -pkcs8}.
     *
     * <p>Only the private key is configured. The public half is derived from it rather than being a
     * second variable to keep in step: an RSA private key in PKCS#8 form is a CRT key, which carries
     * the modulus and the public exponent, so the two can never disagree.
     */
    private static RSAKey fromPem(String pem) {
        String base64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            RSAPrivateKey privateKey = (RSAPrivateKey) factory.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));

            if (!(privateKey instanceof RSAPrivateCrtKey crt)) {
                throw new IllegalStateException(
                        "JWT_PRIVATE_KEY must be an RSA private key in PKCS#8 form, so the public key "
                                + "can be derived from it. Generate one with: "
                                + "openssl genpkey -algorithm RSA -pkcs8 -pkeyopt rsa_keygen_bits:2048");
            }

            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                    new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));

            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(UUID.nameUUIDFromBytes(publicKey.getEncoded()).toString())
                    .build();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException ex) {
            // Fail at startup with a sentence that says what to do, rather than at the first login
            // with a stack trace about key specs.
            throw new IllegalStateException(
                    "JWT_PRIVATE_KEY could not be read as a PKCS#8 PEM private key. Generate one with: "
                            + "openssl genpkey -algorithm RSA -pkcs8 -pkeyopt rsa_keygen_bits:2048", ex);
        }
    }

    private static RSAKey generateEphemeralKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_SIZE);
            KeyPair pair = generator.generateKeyPair();

            log.warn("JWT_PRIVATE_KEY is not set - generated an ephemeral RSA keypair. Tokens will "
                    + "stop working when customer-service restarts, and the other services will "
                    + "reject them until their cached JWK set refreshes. Set JWT_PRIVATE_KEY for "
                    + "anything beyond a single local run.");

            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("This JVM has no RSA KeyPairGenerator", ex);
        }
    }
}
