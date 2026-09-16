package com.ecomdemo.customer;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The authenticated principal: Spring Security's view of a {@link Customer}.
 *
 * <p>Spring Security speaks {@link UserDetails}, the application speaks {@code Customer}, and this
 * adapter keeps those vocabularies apart - the entity carries no security interfaces and the security
 * layer never sees a JPA object.
 *
 * <p>It carries the customer id, which is the point. Without it every request that needs "the current
 * user's cart" would start with a lookup by email to turn a username back into an id. The id is
 * established once, at authentication, and travels with the principal.
 *
 * <p>The {@code ROLE_} prefix is added here rather than stored in the database. Spring Security's
 * {@code hasRole("ADMIN")} is shorthand for the authority {@code ROLE_ADMIN}; keeping the prefix out
 * of the column means the data says {@code ADMIN} and only the framework's convention is in code.
 */
public class SecurityUser implements UserDetails {

    private final Long id;
    private final String email;
    private final String passwordHash;
    private final Customer.Role role;

    public SecurityUser(Customer customer) {
        this.id = customer.getId();
        this.email = customer.getEmail();
        this.passwordHash = customer.getPasswordHash();
        this.role = customer.getRole();
    }

    public Long getId() {
        return id;
    }

    public Customer.Role getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    /** What {@code PasswordEncoder.matches} compares the submitted password against. */
    @Override
    public String getPassword() {
        return passwordHash;
    }

    /** The email is the username: it is what a client sends in the HTTP Basic header. */
    @Override
    public String getUsername() {
        return email;
    }
}
