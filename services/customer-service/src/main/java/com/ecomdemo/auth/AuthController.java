package com.ecomdemo.auth;

import com.ecomdemo.auth.dto.LoginRequest;
import com.ecomdemo.auth.dto.TokenResponse;
import com.ecomdemo.shared.security.SecurityUser;

import jakarta.validation.Valid;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one place credentials are still accepted.
 *
 * <p>This is the authorization server half of OAuth2, collapsed into the same application as the
 * resource server. Splitting them - letting Keycloak or Entra ID own this endpoint and the user
 * table, while this application only verifies tokens - would change this class and nothing else,
 * which is the point of validating a signed token rather than a session.
 *
 * <p>The endpoint is public, necessarily: requiring a token to obtain a token has no base case.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final TokenService tokenService;

    public AuthController(AuthenticationManager authenticationManager, TokenService tokenService) {
        this.authenticationManager = authenticationManager;
        this.tokenService = tokenService;
    }

    /**
     * Exchanges an email and password for a signed token.
     *
     * <p>The credentials are checked by the same {@code AuthenticationManager} the filter chain used
     * for HTTP Basic in Phase 8 - {@code CustomerDetailsService} loads the user,
     * {@code PasswordEncoder} compares the BCrypt hash. Only what happens afterwards has changed: a
     * token is returned instead of the request simply being allowed through.
     *
     * <p>A bad password throws {@code AuthenticationException}, which {@code GlobalExceptionHandler}
     * turns into a 401 that says neither whether the account exists nor which half was wrong.
     */
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));

        SecurityUser user = (SecurityUser) authentication.getPrincipal();
        return TokenResponse.bearer(
                tokenService.issue(user),
                tokenService.getTokenLifetime().toSeconds());
    }
}
