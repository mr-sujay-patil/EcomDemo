package com.ecomdemo.common;

/**
 * The single error shape every failing request returns: {@code { "status": 404, "message": "..." }}.
 *
 * <p>A record is ideal here - it is immutable, has no behaviour, and Jackson serialises its
 * components directly as JSON fields.
 */
public record ApiError(int status, String message) {
}
