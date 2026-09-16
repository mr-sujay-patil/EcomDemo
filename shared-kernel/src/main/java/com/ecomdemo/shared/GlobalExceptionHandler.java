package com.ecomdemo.shared;

import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates exceptions thrown anywhere below a controller into the single {@link ApiError} shape.
 *
 * <p>Without this, Spring Boot's default error page would leak exception class names and, for an
 * unhandled {@code RuntimeException}, return 500 for what is really a client mistake. Centralising
 * the mapping here means no controller needs a try/catch and no service needs to know about HTTP.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** The requested entity does not exist. */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /** The request is valid but the server's current state forbids it (stock, empty cart). */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage());
    }

    /**
     * A {@code @Valid @RequestBody} failed Bean Validation. Every violated constraint is reported,
     * so the client can fix the whole payload in one round trip rather than one field at a time.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBodyValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::describe)
                .sorted()
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, message.isEmpty() ? "Validation failed" : message);
    }

    /** A constraint on a method parameter (a path variable or request param) was violated. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handleParameterValidation(HandlerMethodValidationException ex) {
        String message = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream())
                .map(error -> error.getDefaultMessage() == null ? "invalid value" : error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, message.isEmpty() ? "Validation failed" : message);
    }

    /** Malformed JSON, or a value Jackson could not bind (a string where a number is expected). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return build(HttpStatus.BAD_REQUEST, "Malformed or unreadable request body");
    }

    /** A path variable or request param could not be converted, e.g. /api/products/abc. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return build(HttpStatus.BAD_REQUEST, "Parameter '" + ex.getName() + "' has an invalid value");
    }

    /**
     * No handler and no static resource matched the URL. Spring raises this for any unknown path;
     * without an explicit mapping it would fall through to the catch-all below and be reported as a
     * 500, turning every typo in a URL into a fake server error.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "No endpoint " + ex.getResourcePath());
    }

    /**
     * The optimistic lock rejected a write and the retries ran out.
     *
     * <p>409 rather than 500: nothing is broken and the request was not malformed. Another
     * transaction simply changed the same product first, repeatedly - the same category of answer as
     * "not enough stock", which is what the caller will usually find on trying again.
     *
     * <p>Hibernate's own {@code OptimisticLockException} is translated into this Spring exception by
     * the persistence exception translation that {@code @Repository} on Spring Data interfaces brings
     * in, which is why the handler can stay free of JPA types.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(OptimisticLockingFailureException ex) {
        log.warn("Optimistic lock conflict after retries: {}", ex.getMessage());
        return build(HttpStatus.CONFLICT,
                "Another request changed this data at the same time; please retry");
    }

    /**
     * Login failed: no such account, or the wrong password.
     *
     * <p>This one belongs here rather than in {@link ApiErrorResponder}, unlike the other 401. A
     * failed login happens <em>inside</em> a controller - {@code AuthController} calls the
     * {@code AuthenticationManager} itself - so the exception reaches this advice normally, where
     * without a mapping the catch-all below would report a wrong password as a 500.
     *
     * <p>The message distinguishes nothing. Saying "no such user" would turn this endpoint into a way
     * to find out which email addresses are registered.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleFailedLogin(AuthenticationException ex) {
        log.debug("Failed login attempt: {}", ex.getMessage());
        return build(HttpStatus.UNAUTHORIZED, "Invalid email or password");
    }

    /**
     * Something this service depends on is not answering.
     *
     * <p>503, and deliberately not 409. The request was fine and the state was fine; we are the
     * problem, and nothing the caller changes will help. See {@link ServiceUnavailableException} for
     * why that distinction is worth a separate exception type.
     *
     * <p>Logged at WARN rather than ERROR: a dependency being briefly unavailable is an expected
     * operating condition in a distributed system - it is what the circuit breaker exists to handle -
     * and logging it at ERROR would train everybody to ignore ERROR. The Retry-After header tells a
     * well-behaved client roughly when to come back, which is the only actionable thing there is to
     * say.
     */
    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ApiError> handleServiceUnavailable(ServiceUnavailableException ex) {
        log.warn("Dependency unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "10")
                .body(new ApiError(HttpStatus.SERVICE_UNAVAILABLE.value(), ex.getMessage()));
    }

    /** An argument a service rejected outright. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * Anything unanticipated. The real cause is logged for the operator; the client gets a generic
     * message so internals are never exposed over the wire.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error");
    }

    private static String describe(FieldError error) {
        return error.getField() + " " + error.getDefaultMessage();
    }

    private static ResponseEntity<ApiError> build(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ApiError(status.value(), message));
    }
}
