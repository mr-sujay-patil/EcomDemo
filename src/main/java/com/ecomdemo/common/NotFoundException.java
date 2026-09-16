package com.ecomdemo.common;

/**
 * Thrown by a service when a requested entity does not exist. Mapped to HTTP 404 by
 * {@link GlobalExceptionHandler}.
 *
 * <p>The service layer throws a domain exception rather than returning {@code null} or an HTTP
 * status, which keeps it free of any knowledge of the web layer.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }

    public static NotFoundException of(String entity, Object id) {
        return new NotFoundException(entity + " " + id + " not found");
    }
}
