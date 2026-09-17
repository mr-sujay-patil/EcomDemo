package com.ecomdemo.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Credentials, sent once. Every request after this carries the token instead.
 */
public record LoginRequest(

        @NotBlank(message = "must not be blank")
        String email,

        @NotBlank(message = "must not be blank")
        String password) {
}
