package com.ecomdemo.customer.dto;

import java.time.Instant;

import com.ecomdemo.customer.Customer;

/**
 * The public view of a user.
 *
 * <p>There is no password field of any kind - not the plaintext, not the hash. A hash is still
 * sensitive: it is the input to an offline guessing attack, and once leaked it cannot be un-leaked.
 * Keeping the response a separate record from the entity is what makes that omission permanent
 * rather than something a future field addition could undo.
 */
public record CustomerResponse(
        Long id,
        String email,
        String displayName,
        String role,
        Instant createdAt) {

    public static CustomerResponse from(Customer customer) {
        return new CustomerResponse(
                customer.getId(),
                customer.getEmail(),
                customer.getDisplayName(),
                customer.getRole().name(),
                customer.getCreatedAt());
    }
}
