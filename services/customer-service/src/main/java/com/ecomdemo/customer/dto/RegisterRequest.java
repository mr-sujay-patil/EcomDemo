package com.ecomdemo.customer.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Registration payload.
 *
 * <p>Note what a client cannot send: a {@code role}. Registration always produces a CUSTOMER, and
 * making that structural rather than a check in the service means no future refactor can accidentally
 * let a caller promote themselves to ADMIN. Administrators are seeded by migration.
 */
public record RegisterRequest(

        @NotBlank(message = "must not be blank")
        @Email(message = "must be a valid email address")
        @Size(max = 255, message = "must be at most 255 characters")
        String email,

        /*
         * The minimum is deliberately modest. Length is the only property worth enforcing here -
         * composition rules ("one capital, one symbol") shrink the search space an attacker has to
         * cover and push people towards predictable substitutions.
         */
        @NotBlank(message = "must not be blank")
        @Size(min = 8, max = 72, message = "must be between 8 and 72 characters")
        String password,

        @Size(max = 100, message = "must be at most 100 characters")
        String displayName) {
}
