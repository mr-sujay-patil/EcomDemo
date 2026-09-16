package com.ecomdemo.shared.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Turns a verified token back into the principal the rest of the application already understands.
 *
 * <p>By default a resource server puts a {@link Jwt} in the security context, so
 * {@code @AuthenticationPrincipal} yields raw claims and every controller has to know the token's
 * shape. This converter rebuilds a {@link SecurityUser} from those claims instead, so controllers and
 * {@code @PreAuthorize} expressions written in Phase 8 keep working untouched. How a caller proved
 * who they are is not the business layer's concern.
 *
 * <p>Nothing is read from the database here - and after Phase 20 there is frequently no database to
 * read. In order-service, {@code jwt.getSubject()} is the only thing that ever identifies a customer;
 * the {@code users} table is in another process entirely, and asking it on every request would turn
 * one network hop into two and make every service unavailable whenever customer-service was.
 *
 * <p>The authorities come from {@code SecurityUser} itself rather than being derived here, so the
 * {@code ROLE_} prefix is applied in one place and a token cannot grant an authority the application
 * would never otherwise mint.
 *
 * <p>Registered by {@link com.ecomdemo.shared.autoconfigure.ResourceServerAutoConfiguration} rather
 * than by {@code @Component}: a library cannot rely on being inside somebody else's component scan.
 */
public class JwtSecurityUserConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        SecurityUser principal = new SecurityUser(
                Long.valueOf(jwt.getSubject()),
                jwt.getClaimAsString("email"),
                Role.valueOf(jwt.getClaimAsString("role")));

        // The token itself is kept as the credentials - it is what was presented, and order-service
        // reads it back to forward the caller's identity to catalog-service and inventory-service.
        //
        // SecurityUserAuthentication rather than UsernamePasswordAuthenticationToken, and the choice
        // is load-bearing: that class nulls its credentials in eraseCredentials(), which
        // ProviderManager calls on every successful authentication. The token was being destroyed
        // microseconds after being attached. See SecurityUserAuthentication for why a bearer token
        // and a password want opposite treatment here.
        return new SecurityUserAuthentication(principal, jwt, principal.getAuthorities());
    }
}
