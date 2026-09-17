package com.ecomdemo.shared.security;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The authenticated principal - who the caller is, as every service sees them.
 *
 * <p>It carries the customer id, which is the point. Without it every request that needs "the current
 * user's cart" would start with a lookup by email to turn a username back into an id. The id is
 * established once, at authentication, and travels with the principal.
 *
 * <h2>What Phase 20 changed</h2>
 *
 * This class used to take a {@code Customer} in its constructor - it was an adapter from the JPA
 * entity to Spring Security's {@link UserDetails}. It cannot be that any more, because four of the
 * five services have no users table to load a {@code Customer} from. What they have is a verified
 * token, so the constructors below take plain values and the mapping from the entity lives in
 * customer-service, the one service that owns the row.
 *
 * <p>That inversion is the whole shape of distributed authentication in miniature. Identity stops
 * being something a service <em>looks up</em> and becomes something a request <em>carries</em>, proved
 * by a signature rather than by a foreign key. It is also why the principal is cheap: order-service
 * knows who you are without ever having heard of customer-service.
 *
 * <p>The {@code ROLE_} prefix is added here rather than stored in the database or put in the token.
 * Spring Security's {@code hasRole("ADMIN")} is shorthand for the authority {@code ROLE_ADMIN};
 * keeping the prefix in one place means the data says {@code ADMIN} and only the framework's
 * convention is in code.
 */
public class SecurityUser implements UserDetails {

    private final Long id;
    private final String email;
    private final String passwordHash;
    private final Role role;

    /**
     * From the database, at login: this is the one that carries a password to check.
     *
     * <p>Only customer-service ever uses it, because only customer-service has a password hash to
     * pass in.
     */
    public SecurityUser(Long id, String email, String passwordHash, Role role) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    /**
     * From a verified JWT's claims, on every subsequent request, in every service.
     *
     * <p>No password, because there is nothing to check: the signature already proved the token was
     * issued by customer-service and has not been altered. No database read either - everything a
     * request needs about the caller travelled in the token, which is what "stateless" means in
     * practice and what lets four services authorize a request without a network call.
     *
     * <p>The cost is that the token is a snapshot. Demote a user to CUSTOMER and their existing ADMIN
     * token keeps working until it expires, because nothing re-reads the row. That was true in the
     * monolith too; the split widens it, because the row now lives in a different process from the
     * services trusting the claim.
     */
    public SecurityUser(Long id, String email, Role role) {
        this(id, email, null, role);
    }

    public Long getId() {
        return id;
    }

    public Role getRole() {
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

    /** The email is the username: it is what a client sends when logging in. */
    @Override
    public String getUsername() {
        return email;
    }
}
