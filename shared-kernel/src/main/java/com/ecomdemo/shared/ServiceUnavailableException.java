package com.ecomdemo.shared;

/**
 * Thrown when this service cannot do its job because something it depends on is not answering.
 * Mapped to HTTP 503 by {@link GlobalExceptionHandler}.
 *
 * <h2>Why this is not a {@link ConflictException}</h2>
 *
 * Until Phase 22 a catalogue that could not be reached was reported as a 409, and that was wrong in
 * a way worth being precise about, because the distinction is the whole point of having status codes
 * rather than a single "it failed".
 *
 * <ul>
 *   <li><strong>400</strong> - fix your request.
 *   <li><strong>409</strong> - your request is fine, but the server's state forbids it. Not enough
 *       stock. An empty cart. <em>Something the caller can act on</em>: change the cart and try again.
 *   <li><strong>503</strong> - your request is fine, the state is fine, and <em>we</em> are broken.
 *       Nothing the caller changes will help; the only useful advice is to try again later.
 * </ul>
 *
 * <p>Telling a shopper their cart is no longer available when the truth is that catalog-service is
 * down sends them to rebuild a cart that was never the problem, and they hit the same wall. It also
 * lies to everything upstream: a 409 is a normal business outcome that nobody alerts on, while a 503
 * is the signal that something needs attention. Reporting an outage as a conflict makes the outage
 * invisible on the dashboard.
 *
 * <p>4xx means the client is wrong; 5xx means the server is. A dependency failing is squarely the
 * second, however inconvenient that is for the error-rate graph.
 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
