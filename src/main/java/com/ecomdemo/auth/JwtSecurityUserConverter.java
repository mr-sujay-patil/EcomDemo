package com.ecomdemo.auth;

import com.ecomdemo.customer.Customer;
import com.ecomdemo.customer.SecurityUser;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Turns a verified token back into the principal the rest of the application already understands.
 *
 * <p>By default a resource server puts a {@link Jwt} in the security context, so
 * {@code @AuthenticationPrincipal} yields raw claims and every controller has to know the token's
 * shape. This converter rebuilds a {@link SecurityUser} from those claims instead, so the eight
 * controllers and three {@code @PreAuthorize} expressions written in Phase 8 keep working untouched -
 * and would keep working again if authentication changed a third time. How a caller proved who they
 * are is not the business layer's concern.
 *
 * <p>Nothing is read from the database here. The signature already established that these claims are
 * ours and unaltered, and going back to the {@code users} table on every request would reintroduce
 * exactly the per-request lookup that stateless authentication exists to avoid.
 *
 * <p>The authorities come from {@code SecurityUser} itself rather than being derived here, so the
 * {@code ROLE_} prefix is applied in one place and a token cannot grant an authority the application
 * would never otherwise mint.
 */
@Component
public class JwtSecurityUserConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        SecurityUser principal = new SecurityUser(
                Long.valueOf(jwt.getSubject()),
                jwt.getClaimAsString("email"),
                Customer.Role.valueOf(jwt.getClaimAsString("role")));

        // The token itself is kept as the credentials - it is what was presented, and it stays
        // available to anything that wants to inspect a raw claim.
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, jwt, principal.getAuthorities());
    }
}
