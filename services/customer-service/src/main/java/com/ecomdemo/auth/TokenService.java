package com.ecomdemo.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.ecomdemo.shared.security.SecurityUser;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Mints the access token.
 *
 * <h2>What a JWT actually is</h2>
 *
 * Three base64url segments joined by dots: {@code header.payload.signature}.
 *
 * <ul>
 *   <li><strong>Header</strong> - the algorithm, here {@code RS256}, and the {@code kid} naming
 *       which key signed this one.
 *   <li><strong>Payload</strong> - the claims set below. It is <em>encoded, not encrypted</em>:
 *       anyone holding the token can read every claim by base64-decoding the middle segment. Put
 *       nothing in it you would not put on a postcard.
 *   <li><strong>Signature</strong> - RSASSA-PKCS1-v1_5 with SHA-256 over the first two segments,
 *       using the <em>private</em> key. This is what makes the payload trustworthy without being
 *       secret: change a single character of the payload and the signature no longer matches, and
 *       producing a matching one requires a key only this service holds. Phase 9 used HS256 here,
 *       where the verifying key and the signing key were the same - see {@link JwtKeyConfiguration}
 *       for why five services made that untenable.
 * </ul>
 *
 * <h2>The claims, and why these</h2>
 *
 * {@code sub} is the customer id rather than the email, because the id is what every controller and
 * query needs and it never changes. {@code email} rides along for logging and display. {@code role}
 * is what the authorization rules run on, so the token carries the authority decision itself - which
 * is what lets a request be authorized with no database read at all.
 *
 * <p>After Phase 20 that last point stops being an optimisation and becomes the design. order-service
 * has no users table; the {@code sub} claim is the only thing that tells it whose cart to load. A
 * claim is not a convenience here, it is the entire identity contract between five processes - which
 * is also why nothing sensitive may go in one. The payload is base64, not encryption.
 */
@Service
public class TokenService {

    private final JwtEncoder jwtEncoder;
    private final Clock clock;
    private final Duration tokenLifetime;

    public TokenService(JwtEncoder jwtEncoder,
                        Clock clock,
                        @Value("${ecomdemo.jwt.expiry:15m}") Duration tokenLifetime) {
        this.jwtEncoder = jwtEncoder;
        this.clock = clock;
        this.tokenLifetime = tokenLifetime;
    }

    public Duration getTokenLifetime() {
        return tokenLifetime;
    }

    /**
     * Issues a token for an authenticated user.
     *
     * <p>The expiry is short on purpose. A JWT cannot be withdrawn once issued - there is no server
     * state to delete, which is precisely what makes it stateless - so the window during which a
     * stolen or stale token still works is exactly its remaining lifetime. Fifteen minutes bounds
     * that; lengthening it trades security for fewer logins, and the honest way to shorten it further
     * is a refresh token plus somewhere to revoke it.
     */
    public String issue(SecurityUser user) {
        Instant now = clock.instant();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("ecomdemo")
                .issuedAt(now)
                .expiresAt(now.plus(tokenLifetime))
                .subject(String.valueOf(user.getId()))
                .claim("email", user.getUsername())
                .claim("role", user.getRole().name())
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }
}
