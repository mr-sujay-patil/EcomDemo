package com.ecomdemo.shared.testsupport;

import com.ecomdemo.shared.security.SecurityUser;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

/**
 * Builds the {@link SecurityUser} that {@link WithMockCustomer} installs.
 *
 * <p>The authorities come from {@code SecurityUser} itself rather than being listed here, so a test
 * cannot end up authenticated with a role the production code would never grant.
 */
public class WithMockCustomerSecurityContextFactory
        implements WithSecurityContextFactory<WithMockCustomer> {

    @Override
    public SecurityContext createSecurityContext(WithMockCustomer annotation) {
        // No entity and no reflective id-setting any more: a principal is three values from a
        // token, which is all four of the five services will ever have.
        SecurityUser principal = new SecurityUser(annotation.id(), annotation.email(), annotation.role());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, principal.getPassword(), principal.getAuthorities()));
        return context;
    }
}
