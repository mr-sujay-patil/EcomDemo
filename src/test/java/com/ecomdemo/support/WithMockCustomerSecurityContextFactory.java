package com.ecomdemo.support;

import java.time.Instant;

import com.ecomdemo.customer.Customer;
import com.ecomdemo.customer.SecurityUser;

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
        Customer customer = TestFixtures.withId(
                new Customer(annotation.email(), "irrelevant-hash", "Test Customer",
                        annotation.role(), Instant.parse("2026-01-01T00:00:00Z")),
                annotation.id());

        SecurityUser principal = new SecurityUser(customer);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, principal.getPassword(), principal.getAuthorities()));
        return context;
    }
}
