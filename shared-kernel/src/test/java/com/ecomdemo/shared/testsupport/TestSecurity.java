package com.ecomdemo.shared.testsupport;

import com.ecomdemo.shared.security.Role;
import com.ecomdemo.shared.security.SecurityUser;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Installs an authenticated principal for tests that call services directly rather than over HTTP.
 *
 * <p>{@code @WithMockCustomer} is the right tool when a request goes through the filter chain. These
 * tests do not make requests - they call a service method from a plain method, and the
 * {@code @PreAuthorize} on it needs an {@code Authentication} to evaluate
 * {@code authentication.principal.id} against. Without one the call fails with an authentication
 * error that has nothing to do with what the test is about.
 *
 * <p>Tests that use this must clear the context afterwards. {@code SecurityContextHolder} is
 * thread-local, and a JUnit thread is reused for the next test.
 */
public final class TestSecurity {

    private TestSecurity() {
    }

    /** Act as this customer for the remainder of the current test. */
    public static void actAs(Long customerId, String email) {
        // Built from plain values rather than from a Customer entity, as it was in Phase 8. There is
        // no Customer class in four of the five services now - the principal is what a token says,
        // not what a table holds.
        SecurityUser principal = new SecurityUser(customerId, email, Role.CUSTOMER);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        principal, principal.getPassword(), principal.getAuthorities()));
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}
