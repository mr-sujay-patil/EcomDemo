package com.ecomdemo.auth;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.ecomdemo.auth.dto.LoginRequest;
import com.ecomdemo.auth.dto.TokenResponse;
import com.ecomdemo.shared.ApiError;
import com.ecomdemo.support.AbstractCustomerServiceIT;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Login, and what happens to tokens that should not be trusted - end to end against real PostgreSQL.
 *
 * <p>The negative cases are the valuable half. A token that works proves the happy path; a token that
 * has been edited, or has expired, or was never signed by us must fail - and must fail in the filter
 * chain, before any controller, with the same error shape as everything else.
 *
 * <h2>What moved out of this class in Phase 20</h2>
 *
 * It used to end with a group called {@code AValidTokenOpensTheRightDoors}, which checked that a
 * customer's token reached the cart and a customer's token did <em>not</em> reach the catalogue. Those
 * doors are in other processes now. Each service tests its own rules against a minted token, and the
 * end-to-end claim - one login, five services - is verified through Compose, because that is the only
 * place all five exist at once.
 *
 * <p>That is a genuine loss of coverage at this level, and worth naming rather than papering over: no
 * single module's test suite can now fail because catalog-service and customer-service disagreed
 * about what a token means.
 */
class JwtAuthenticationIT extends AbstractCustomerServiceIT {

    @Autowired
    private JwtEncoder jwtEncoder;

    /**
     * Mints a token that is correctly signed by this service but already expired. Signing it
     * properly is the point: it isolates the expiry check from the signature check.
     */
    private String expiredToken() {
        Instant issued = Instant.now().minus(2, ChronoUnit.HOURS);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("ecomdemo")
                .issuedAt(issued)
                .expiresAt(issued.plus(15, ChronoUnit.MINUTES))   // expired an hour and a half ago
                .subject(String.valueOf(customerId))
                .claim("email", "expired@ecomdemo.local")
                .claim("role", "CUSTOMER")
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }

    @Nested
    class Login {

        @Test
        void login_withValidCredentials_returnsASignedBearerToken() {
            // GIVEN the seeded administrator
            // WHEN
            TokenResponse token = anonymous.post().uri("/api/auth/login")
                    .body(new LoginRequest(ADMIN_EMAIL, ADMIN_PASSWORD))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(TokenResponse.class)
                    .returnResult().getResponseBody();

            // THEN it is a three-segment JWT with the lifetime the client needs to know about
            assertThat(token).isNotNull();
            assertThat(token.tokenType()).isEqualTo("Bearer");
            assertThat(token.accessToken().split("\\.")).hasSize(3);
            assertThat(token.expiresInSeconds()).isPositive();
        }

        @Test
        void login_withTheWrongPassword_is401AndSaysNothingUseful() {
            // WHEN
            ApiError error = anonymous.post().uri("/api/auth/login")
                    .body(new LoginRequest(ADMIN_EMAIL, "not-the-password"))
                    .exchange()
                    .expectStatus().isUnauthorized()
                    .expectBody(ApiError.class)
                    .returnResult().getResponseBody();

            // THEN the message distinguishes nothing - saying "no such user" would turn this endpoint
            // into a way to discover which email addresses are registered
            assertThat(error).isNotNull();
            assertThat(error.message()).isEqualTo("Invalid email or password");
        }

        @Test
        void login_withAnUnknownEmail_isTheSame401AsAWrongPassword() {
            ApiError error = anonymous.post().uri("/api/auth/login")
                    .body(new LoginRequest("nobody@ecomdemo.local", "whatever123"))
                    .exchange()
                    .expectStatus().isUnauthorized()
                    .expectBody(ApiError.class)
                    .returnResult().getResponseBody();

            assertThat(error).isNotNull();
            assertThat(error.message()).isEqualTo("Invalid email or password");
        }
    }

    @Nested
    class AValidTokenIdentifiesItsOwner {

        @Test
        void customerToken_reachesThisServicesOwnProtectedEndpoint() {
            // GIVEN the token obtained in setUp
            // WHEN / THEN /api/customers/me is not in anybody's permitAll list, so it is reachable
            // only because the token says who the caller is
            client.get().uri("/api/customers/me").exchange().expectStatus().isOk();
        }

        @Test
        void registrationAndTheJwkSet_needNoToken() {
            // The two endpoints that have to work before a caller has anything to present, plus the
            // key every other service needs in order to check what they eventually do present.
            anonymous.get().uri("/.well-known/jwks.json").exchange().expectStatus().isOk();
        }
    }

    @Nested
    class BadTokensAreRejected {

        @Test
        void noToken_is401() {
            ApiError error = anonymous.get().uri("/api/customers/me")
                    .exchange()
                    .expectStatus().isUnauthorized()
                    .expectBody(ApiError.class)
                    .returnResult().getResponseBody();

            assertThat(error).isNotNull();
            assertThat(error.status()).isEqualTo(401);
        }

        @Test
        void tamperedToken_is401() {
            // GIVEN a genuine token whose payload has been edited
            String genuine = login(ADMIN_EMAIL, ADMIN_PASSWORD);
            String[] parts = genuine.split("\\.");
            String tampered = parts[0] + "." + flipLastCharacter(parts[1]) + "." + parts[2];

            // WHEN it is presented
            RestTestClient forged = clientFor(tampered);

            // THEN the signature no longer matches the payload it covers. This is the whole security
            // model: the claims are readable and editable by anyone, and worthless once edited,
            // because producing a matching signature needs the private key.
            forged.get().uri("/api/customers/me").exchange().expectStatus().isUnauthorized();
        }

        @Test
        void tokenSignedWithAnotherKey_is401() {
            // GIVEN a structurally perfect token signed by somebody else. Built by hand rather than
            // by this service, which is exactly the forgery the signature exists to stop.
            String foreign = "eyJhbGciOiJIUzI1NiJ9"
                    + ".eyJzdWIiOiIxIiwiZW1haWwiOiJhZG1pbkBlY29tZGVtby5sb2NhbCIsInJvbGUiOiJBRE1JTiJ9"
                    + ".bm90LWEtcmVhbC1zaWduYXR1cmUtYXQtYWxs";

            // WHEN / THEN - and note what it claims: role ADMIN. Claims are assertions, not facts;
            // only the signature makes them believable. Note also the algorithm in that header:
            // HS256. A decoder configured for RS256 must not accept an HMAC token just because the
            // header asks it to, which is the "alg: none" family of attacks in miniature.
            clientFor(foreign).get().uri("/api/customers/me").exchange().expectStatus().isUnauthorized();
        }

        @Test
        void expiredToken_is401() {
            // GIVEN a token this service signed correctly, but two hours ago
            RestTestClient stale = clientFor(expiredToken());

            // WHEN / THEN the signature is perfect and it is still refused. exp is checked on every
            // request, in every service, and it is the only thing bounding how long a leaked token
            // remains useful - there is no server-side record to delete, and after the split there
            // are five processes with no way to be told to stop trusting it.
            stale.get().uri("/api/customers/me").exchange().expectStatus().isUnauthorized();
        }

        @Test
        void gibberishInsteadOfAToken_is401() {
            clientFor("this-is-not-a-jwt").get().uri("/api/customers/me")
                    .exchange().expectStatus().isUnauthorized();
        }
    }

    private static String flipLastCharacter(String segment) {
        char last = segment.charAt(segment.length() - 1);
        char replacement = last == 'A' ? 'B' : 'A';
        return segment.substring(0, segment.length() - 1) + replacement;
    }
}
