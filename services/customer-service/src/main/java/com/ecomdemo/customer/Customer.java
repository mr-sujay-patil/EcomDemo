package com.ecomdemo.customer;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.ecomdemo.shared.security.Role;

/**
 * A registered user - a shopper or an administrator.
 *
 * <p>The class is {@code Customer} while the table is {@code users}: the domain word for who these
 * people are is customer, but USER is reserved in PostgreSQL. {@code @Table} is where that
 * disagreement is resolved, rather than by quoting an identifier in every query.
 *
 * <p><strong>The password is stored hashed, never encrypted.</strong> Encryption is reversible by
 * design - it exists so that somebody holding the key can read the plaintext back. A password
 * database needs the opposite property: nobody, including us, should be able to recover what the
 * user typed.
 *
 * <p>After Phase 20 this entity exists in exactly one service. No other service has a {@code Customer}
 * class, a users table, or a way to load one - an order knows its owner as a bare {@code customer_id}
 * column with no foreign key behind it. The {@link Role} enum moved to shared-kernel for the same
 * reason: four services must read a role out of a token without owning the row it came from. BCrypt is a one-way function, so {@code passwordHash} cannot be turned back into a
 * password; authentication works by hashing the attempt and comparing. It also salts each hash
 * automatically, so two users who pick the same password still get different rows, and it is
 * deliberately slow, which is what makes guessing expensive.
 */
@Entity
@Table(name = "users")
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stored lower-cased by the service, because PostgreSQL's unique index is case-sensitive. */
    @Column(nullable = false, unique = true)
    private String email;

    /**
     * BCrypt output. Never logged, never returned by any DTO, never compared with {@code equals} -
     * {@code PasswordEncoder.matches} is what checks it, and it re-derives the salt from this string.
     */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Customer() {
    }

    public Customer(String email, String passwordHash, String displayName, Role role, Instant createdAt) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.role = role;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Role getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

}
