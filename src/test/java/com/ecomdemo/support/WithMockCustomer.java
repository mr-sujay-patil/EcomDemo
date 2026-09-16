package com.ecomdemo.support;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import com.ecomdemo.customer.Customer;

import org.springframework.security.test.context.support.WithSecurityContext;

/**
 * {@code @WithMockUser} that carries a customer id.
 *
 * <p>{@code @WithMockUser} installs Spring Security's own {@code User} as the principal, which knows
 * a username and some authorities and nothing else. Every controller here takes
 * {@code @AuthenticationPrincipal SecurityUser} and asks it for {@code getId()}, so a plain
 * {@code @WithMockUser} would inject {@code null} and the test would fail on a NullPointerException
 * that says nothing about authorization.
 *
 * <p>This is the documented way to extend it: {@code @WithSecurityContext} names a factory that
 * builds whatever principal the application actually uses. Tests that only care about a role - the
 * product endpoints, say - still use plain {@code @WithMockUser}, because there is no id involved.
 */
@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithMockCustomerSecurityContextFactory.class)
public @interface WithMockCustomer {

    long id() default 1L;

    String email() default "customer@ecomdemo.local";

    Customer.Role role() default Customer.Role.CUSTOMER;
}
