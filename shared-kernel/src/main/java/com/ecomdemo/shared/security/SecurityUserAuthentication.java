package com.ecomdemo.shared.security;

import java.util.Collection;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * An authenticated caller: a {@link SecurityUser} principal that keeps hold of the token it came
 * from.
 *
 * <h2>Why this exists rather than {@code UsernamePasswordAuthenticationToken}</h2>
 *
 * The converter used to build a {@code UsernamePasswordAuthenticationToken} with the {@link Jwt} as
 * its credentials, and that worked perfectly for two phases because nothing ever read the
 * credentials back.
 *
 * <p>Phase 20 needs to read them: order-service forwards the caller's own token to catalog-service
 * and inventory-service, and it gets that token from {@code authentication.getCredentials()}. At
 * which point the token turns out not to be there.
 *
 * <p>{@code UsernamePasswordAuthenticationToken} overrides {@code eraseCredentials()} to null its
 * credentials field, and {@code ProviderManager} calls {@code eraseCredentials()} on every
 * successful authentication by default. So the token was being deliberately destroyed a few
 * microseconds after the converter attached it - which is exactly right for the password that class
 * is named after, and wrong for a bearer token.
 *
 * <p>This class therefore does <strong>not</strong> erase its credentials, and the distinction is
 * worth being precise about rather than treating as a workaround:
 *
 * <ul>
 *   <li>A <em>password</em> is a long-lived secret the server should hold for as long as it takes to
 *       check it and not one instant longer. Erasing it limits what a heap dump reveals.
 *   <li>A <em>bearer token</em> is a short-lived credential the client already possesses, already
 *       sent in a header, and which this process must be able to present onward in order to act on
 *       the caller's behalf. Erasing it does not protect the caller - they are holding a copy - and
 *       it makes propagating identity impossible.
 * </ul>
 *
 * <p>What this does cost: the token stays reachable in memory for the life of the request, so it
 * would appear in a heap dump taken during one. Bounded by the request and by the token's own
 * fifteen-minute expiry, which is the trade this design already accepts everywhere else.
 *
 * <p>The failure it fixes is worth remembering because of how it presented: catalog-service worked
 * (its reads are public, so the missing header did not matter) and inventory-service answered 401 on
 * checkout. The symptom was "reserving stock is broken", and the cause was a security feature doing
 * its job on the wrong kind of secret.
 */
public class SecurityUserAuthentication extends AbstractAuthenticationToken {

    private final transient SecurityUser principal;
    private final transient Jwt token;

    public SecurityUserAuthentication(SecurityUser principal, Jwt token,
                                      Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        this.token = token;
        // The signature was verified before this object was constructed; there is nothing left to
        // check, and an Authentication that is not authenticated would be rejected by every rule.
        setAuthenticated(true);
    }

    @Override
    public SecurityUser getPrincipal() {
        return principal;
    }

    /**
     * The verified token, as presented.
     *
     * <p>Unchanged, with its original expiry - so a call this service makes on the caller's behalf
     * cannot outlive the authorization that permitted it.
     */
    @Override
    public Jwt getCredentials() {
        return token;
    }

    /**
     * Deliberately does nothing.
     *
     * <p>See the class comment. {@code ProviderManager} calls this on every successful
     * authentication, and the inherited behaviour would throw away the one thing this class exists
     * to carry.
     */
    @Override
    public void eraseCredentials() {
        // Intentionally empty - a bearer token is not a password.
    }
}
