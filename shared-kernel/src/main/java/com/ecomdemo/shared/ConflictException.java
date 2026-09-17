package com.ecomdemo.shared;

/**
 * Thrown when a request is well-formed but conflicts with the current state of the system - for
 * example ordering more units than are in stock, or placing an order from an empty cart. Mapped to
 * HTTP 409.
 *
 * <p>The distinction from a 400 matters: a 400 means "fix your request", a 409 means "your request
 * is fine, but the server's state does not allow it right now".
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
