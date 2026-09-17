package com.ecomdemo.shared;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import tools.jackson.databind.ObjectMapper;

/**
 * Makes 401 and 403 look like every other error this API returns.
 *
 * <p>{@code GlobalExceptionHandler} cannot do this job. It is a {@code @RestControllerAdvice}, which
 * only sees exceptions thrown from a controller - and authentication and authorization failures
 * happen in the servlet filter chain, <em>before</em> any controller is reached. Left alone, Spring
 * Security answers with its own empty body, so a client parsing {@code {status, message}} would get
 * a surprise on exactly the two responses it is most likely to hit.
 *
 * <p>One class implements both hooks because the two cases are one decision told twice:
 *
 * <ul>
 *   <li><strong>401 Unauthorized</strong> ({@link AuthenticationEntryPoint}) - "I do not know who you
 *       are." Sent when credentials are absent or wrong. The name is a historical misnomer; it is
 *       about authentication.
 *   <li><strong>403 Forbidden</strong> ({@link AccessDeniedHandler}) - "I know who you are, and you
 *       may not do this." Sent when a CUSTOMER calls an ADMIN endpoint.
 * </ul>
 *
 * <p>The messages are deliberately vague. "Bad credentials" without saying whether the email existed
 * is what stops this endpoint being used to enumerate registered users.
 *
 * <p>Registered by {@link com.ecomdemo.shared.autoconfigure.SharedKernelAutoConfiguration} rather
 * than by {@code @Component}: this class lives in a library jar, and a library cannot assume it sits
 * inside somebody else's component scan.
 */
public class ApiErrorResponder implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorResponder.class);

    private final ObjectMapper objectMapper;

    public ApiErrorResponder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** No credentials, or credentials that did not check out. */
    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        log.debug("Unauthenticated request to {} {}", request.getMethod(), request.getRequestURI());
        write(response, HttpStatus.UNAUTHORIZED, "Authentication required");
    }

    /** Authenticated, but not allowed. */
    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        log.debug("Forbidden request to {} {}", request.getMethod(), request.getRequestURI());
        write(response, HttpStatus.FORBIDDEN, "You do not have permission to perform this action");
    }

    private void write(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new ApiError(status.value(), message));
    }
}
