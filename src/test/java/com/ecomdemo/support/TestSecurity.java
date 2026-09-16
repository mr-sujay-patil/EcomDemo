package com.ecomdemo.support;

import com.ecomdemo.customer.Customer;
import com.ecomdemo.customer.SecurityUser;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Installs an authenticated principal for tests that call services directly rather than over HTTP.
 *
 * <p>{@code @WithMockCustomer} is the right tool when a request goes through the filter chain. These
 * tests do not make requests - they call {@code OrderService.placeOrder(id)} from a plain method, and
 * the {@code @PreAuthorize} on it needs an {@code Authentication} to evaluate
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
        Customer customer = TestFixtures.customer(customerId, email);
        SecurityUser principal = new SecurityUser(customer);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        principal, principal.getPassword(), principal.getAuthorities()));
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}
