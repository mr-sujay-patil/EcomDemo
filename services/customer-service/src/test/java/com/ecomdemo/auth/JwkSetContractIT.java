package com.ecomdemo.auth;

import java.util.List;
import java.util.Map;

import com.ecomdemo.support.AbstractCustomerServiceIT;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;

import org.junit.jupiter.api.Test;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The contract between customer-service and the other four, tested from the outside.
 *
 * <p>This is the single most important test in this service after Phase 20, because it is the only
 * one that checks the thing the whole system's security rests on: that a service holding <em>nothing
 * but what this endpoint publishes</em> can verify a token this service issued, and cannot produce
 * one of its own.
 *
 * <p>It deliberately uses no internal bean. It fetches the JWK set over HTTP, builds a decoder from
 * the JSON exactly as {@code NimbusJwtDecoder.withJwkSetUri(...)} would in catalog-service, and
 * verifies a token obtained by logging in over HTTP. Autowiring the {@code RSAKey} would be easier
 * and would prove nothing - it would be this service checking its own homework with the answer sheet.
 */
class JwkSetContractIT extends AbstractCustomerServiceIT {

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchJwkSet() {
        return anonymous.get().uri("/.well-known/jwks.json")
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
    }

    @Test
    void theJwkSet_isPublic_becauseAServiceCannotAuthenticateToFetchTheKeyThatValidatesAuthentication() {
        // GIVEN no credentials at all
        // WHEN / THEN it answers. Requiring a token here would be circular, and there is nothing to
        // protect: a public key is meant to be given away.
        assertThat(fetchJwkSet()).containsKey("keys");
    }

    @Test
    void theJwkSet_never_containsPrivateKeyMaterial() {
        // GIVEN the published set
        Map<String, Object> jwks = fetchJwkSet();

        // WHEN the key is read
        List<Map<String, Object>> keys = (List<Map<String, Object>>) jwks.get("keys");

        // THEN it has the public RSA parameters - modulus and exponent - and none of the private
        // ones. "d" is the private exponent; "p", "q", "dp", "dq" and "qi" are the CRT factors.
        // Publishing any of them would hand every reader the ability to mint an admin token, which
        // is precisely the failure mode Phase 9's shared HMAC secret had by construction.
        assertThat(keys).singleElement().satisfies(key -> {
            assertThat(key).containsKeys("kty", "n", "e", "kid");
            assertThat(key).doesNotContainKeys("d", "p", "q", "dp", "dq", "qi");
        });
    }

    @Test
    void aTokenFromLogin_isVerifiable_usingOnlyThePublishedKey() throws Exception {
        // GIVEN a decoder built the way another service builds one: from the published JSON, with no
        // access to anything inside this process
        RSAKey published = (RSAKey) JWKSet.parse(fetchJwkSet()).getKeys().get(0);
        JwtDecoder asAnotherServiceWould = NimbusJwtDecoder.withPublicKey(published.toRSAPublicKey()).build();

        // WHEN a real login token is verified with it
        Jwt decoded = asAnotherServiceWould.decode(login(customerEmail, CUSTOMER_PASSWORD));

        // THEN the claims the other four services authorize on are all there and correct. This is
        // order-service's entire knowledge of who you are.
        assertThat(decoded.getSubject()).isEqualTo(String.valueOf(customerId));
        assertThat(decoded.getClaimAsString("email")).isEqualTo(customerEmail);
        assertThat(decoded.getClaimAsString("role")).isEqualTo("CUSTOMER");
    }

    @Test
    void aTokenSignedByAnybodyElse_isRejected_byThatSamePublishedKey() throws Exception {
        // GIVEN the published key, and a token signed by a different keypair - which is what a
        // compromised notification-service would have to produce in order to forge an identity
        RSAKey published = (RSAKey) JWKSet.parse(fetchJwkSet()).getKeys().get(0);
        JwtDecoder asAnotherServiceWould = NimbusJwtDecoder.withPublicKey(published.toRSAPublicKey()).build();

        String forged = com.ecomdemo.shared.testsupport.TestTokens.issueAdmin();

        // WHEN / THEN it does not verify. The public key can check a signature and cannot create one,
        // which is the entire reason it is safe to give all five services a copy.
        assertThatThrownBy(() -> asAnotherServiceWould.decode(forged))
                .hasMessageContaining("Invalid signature");
    }
}
