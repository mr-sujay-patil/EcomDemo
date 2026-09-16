package com.ecomdemo.auth.dto;

/**
 * What a successful login returns.
 *
 * <p>{@code tokenType} is {@code Bearer} and says how to use the token: put it in an
 * {@code Authorization: Bearer <token>} header. "Bearer" is meant literally - possession is the only
 * qualification, exactly like a train ticket. Nothing binds the token to the machine that obtained
 * it, which is why it must never travel over plain HTTP and why the expiry is short.
 *
 * <p>{@code expiresInSeconds} saves the client from having to decode the token to find out when to
 * log in again. There is deliberately no refresh token here - see docs/decisions.md.
 */
public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds) {

    public static TokenResponse bearer(String accessToken, long expiresInSeconds) {
        return new TokenResponse(accessToken, "Bearer", expiresInSeconds);
    }
}
