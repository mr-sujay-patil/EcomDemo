package com.ecomdemo.shared.security;

/**
 * What a caller is allowed to be.
 *
 * <p>This enum used to be nested inside the {@code Customer} entity, which was right while there was
 * one application: the database column and the authorization rule were two views of the same field.
 * Phase 20 separated them. {@code customer-service} still owns the {@code users.role} column and is
 * the only service that can write it, but all five services have to <em>read</em> a role out of a
 * JWT and decide whether to allow a request - and four of them have no {@code Customer} entity, no
 * users table, and no way to get one.
 *
 * <p>So the role travels in the token and this enum is part of the token's contract rather than part
 * of anybody's schema. That has a versioning consequence worth stating plainly: adding a value here
 * means every service must be able to parse it <em>before</em> customer-service starts issuing it,
 * or the new role becomes an {@code IllegalArgumentException} on a perfectly valid token. Deploy the
 * readers first, then the writer.
 *
 * <p>Spring Security expects authorities named {@code ROLE_CUSTOMER} and {@code ROLE_ADMIN}; the
 * prefix is added when the authority is built, not stored here and not put in the token, so both the
 * column and the claim hold the word a human would use.
 */
public enum Role {
    CUSTOMER,
    ADMIN
}
