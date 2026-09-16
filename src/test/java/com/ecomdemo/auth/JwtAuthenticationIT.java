package com.ecomdemo.auth;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.ecomdemo.auth.dto.LoginRequest;
import com.ecomdemo.auth.dto.TokenResponse;
import com.ecomdemo.common.ApiError;
import com.ecomdemo.support.AbstractPostgresIT;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The phase's "done when", end to end against real PostgreSQL: every secured endpoint works with a
 * bearer token, and expired or tampered tokens are rejected.
 *
 * <p>The negative cases are the valuable half. A token that works proves the happy path; a token that
 * has been edited, or has expired, or was never signed by us must fail - and must fail in the filter
 * chain, before any controller, with the same error shape as everything else.
 */
class JwtAuthenticationIT extends AbstractPostgresIT {

    @Autowired
    private JwtEncoder jwtEncoder;

    /**
     * Mints a token that is correctly signed by this application but already expired. Signing it
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
                JwsHeader.with(() -> "HS256").build(), claims)).getTokenValue();
    }

    @Nested
    class Login {

        @Test
        void login_withValidCredentials_returnsASignedBearerToken() {
            // GIVEN the seeded administrator
            // WHEN
            TokenResponse token = anonymous.post().uri("/api/auth/login")
                    .body(new LoginRequest("admin@ecomdemo.local", "admin123"))
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
                    .body(new LoginRequest("admin@ecomdemo.local", "not-the-password"))
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
    class AValidTokenOpensTheRightDoors {

        @Test
        void customerToken_reachesTheCartAndOrders() {
            // GIVEN the token obtained in setUp
            // WHEN / THEN the endpoints Phase 8 secured now work on the strength of a token alone
            assertThat(client.get().uri("/api/cart").exchange()
                    .expectStatus().isOk().returnResult().getStatus())
                    .isEqualTo(HttpStatus.OK);

            client.get().uri("/api/orders").exchange().expectStatus().isOk();
            client.get().uri("/api/customers/me").exchange().expectStatus().isOk();
        }

        @Test
        void adminToken_mayWriteToTheCatalogue() {
            admin.post().uri("/api/products")
                    .body(new com.ecomdemo.product.dto.ProductRequest(
                            "JWT Widget " + System.nanoTime(), "written with a token",
                            new java.math.BigDecimal("15.00"), 5, null))
                    .exchange()
                    .expectStatus().isCreated();
        }

        @Test
        void customerToken_stillMayNotWriteToTheCatalogue() {
            // The role travelled in the token, and the rules run on it exactly as they ran on the
            // authority derived from the database in Phase 8.
            client.delete().uri("/api/products/1").exchange().expectStatus().isForbidden();
        }

        @Test
        void anyToken_isUnnecessaryForPublicReads() {
            anonymous.get().uri("/api/products").exchange().expectStatus().isOk();
        }
    }

    @Nested
    class BadTokensAreRejected {

        @Test
        void noToken_is401() {
            ApiError error = anonymous.get().uri("/api/cart")
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
            String genuine = login("admin@ecomdemo.local", "admin123");
            String[] parts = genuine.split("\\.");
            String tampered = parts[0] + "." + flipLastCharacter(parts[1]) + "." + parts[2];

            // WHEN it is presented
            RestTestClient forged = clientFor(tampered);

            // THEN the signature no longer matches the payload it covers. This is the whole security
            // model: the claims are readable and editable by anyone, and worthless once edited,
            // because producing a matching signature needs the key.
            forged.get().uri("/api/cart").exchange().expectStatus().isUnauthorized();
        }

        @Test
        void tokenSignedWithAnotherKey_is401() {
            // GIVEN a structurally perfect token signed by somebody else. Built by hand rather than
            // by this application, which is exactly the forgery the signature exists to stop.
            String foreign = "eyJhbGciOiJIUzI1NiJ9"
                    + ".eyJzdWIiOiIxIiwiZW1haWwiOiJhZG1pbkBlY29tZGVtby5sb2NhbCIsInJvbGUiOiJBRE1JTiJ9"
                    + ".bm90LWEtcmVhbC1zaWduYXR1cmUtYXQtYWxs";

            // WHEN / THEN - and note what it claims: role ADMIN. Claims are assertions, not facts;
            // only the signature makes them believable.
            clientFor(foreign).get().uri("/api/cart").exchange().expectStatus().isUnauthorized();
        }

        @Test
        void expiredToken_is401() {
            // GIVEN a token this application signed correctly, but two hours ago
            RestTestClient stale = clientFor(expiredToken());

            // WHEN / THEN the signature is perfect and it is still refused. exp is checked on every
            // request, and it is the only thing bounding how long a leaked token remains useful -
            // there is no server-side record to delete.
            stale.get().uri("/api/cart").exchange().expectStatus().isUnauthorized();
        }

        @Test
        void gibberishInsteadOfAToken_is401() {
            clientFor("this-is-not-a-jwt").get().uri("/api/cart")
                    .exchange().expectStatus().isUnauthorized();
        }
    }

    private static String flipLastCharacter(String segment) {
        char last = segment.charAt(segment.length() - 1);
        char replacement = last == 'A' ? 'B' : 'A';
        return segment.substring(0, segment.length() - 1) + replacement;
    }
}
