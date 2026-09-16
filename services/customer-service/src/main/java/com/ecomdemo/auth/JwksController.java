package com.ecomdemo.auth;

import java.util.Map;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the public half of the signing key, so the other four services can verify tokens without
 * being able to issue them.
 *
 * <h2>Why this endpoint is anonymous, and why that is safe</h2>
 *
 * It is a public key. Its entire purpose is to be given away: with it you can check that a token was
 * signed by customer-service, and you cannot produce a token of your own. Requiring a credential to
 * fetch it would also be circular - a service needs this key in order to validate the credential it
 * would have to present to get it.
 *
 * <p>This is the concrete difference from Phase 9's HMAC secret, which could never be published
 * because verifying and signing were the same operation with the same key.
 *
 * <h2>The path</h2>
 *
 * {@code /.well-known/jwks.json} is the conventional location (RFC 8615 reserves {@code /.well-known/}
 * for exactly this kind of metadata), and it is what Spring's {@code NimbusJwtDecoder} expects when a
 * service is configured with {@code jwk-set-uri}. Nothing enforces the name - the other services are
 * pointed at this URL explicitly - but using the convention means a real identity provider could
 * replace this service later with a configuration change and no code change.
 *
 * <h2>What a consumer does with it</h2>
 *
 * Fetches it once, lazily, on the first token it has to verify, and caches the result. So this
 * endpoint is not in the hot path: order-service does not call customer-service per request, or per
 * minute. It also means customer-service being down does not stop the other four authorizing
 * requests - they already have the key.
 */
@RestController
public class JwksController {

    private final JWKSet publicKeys;

    public JwksController(RSAKey rsaKey) {
        // toPublicJWK() strips the private key material. Getting this wrong would publish the
        // signing key to anyone who asked, which is why the conversion happens once here rather
        // than being left to the serialisation step.
        this.publicKeys = new JWKSet(rsaKey.toPublicJWK());
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return publicKeys.toJSONObject();
    }
}
