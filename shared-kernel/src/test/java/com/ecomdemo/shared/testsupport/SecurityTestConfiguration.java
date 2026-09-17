package com.ecomdemo.shared.testsupport;

import com.ecomdemo.shared.ApiErrorResponder;
import com.ecomdemo.shared.autoconfigure.ResourceServerAutoConfiguration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import tools.jackson.databind.ObjectMapper;

/**
 * Everything a {@code @WebMvcTest} needs to exercise the real security rules, in any of the five
 * services.
 *
 * <p>A slice loads controllers, not {@code @Configuration} classes - and, importantly, not
 * auto-configurations either, beyond the short web-related list {@code @WebMvcTest} includes. So the
 * shared filter chain has to be asked for by name; without it the slice silently falls back to
 * Boot's defaults and every authorization test passes while testing nothing.
 *
 * <ul>
 *   <li>{@link ResourceServerAutoConfiguration} - the filter chain itself, plus the converter that
 *       turns claims into a {@code SecurityUser}. The service's own
 *       {@code ServiceAuthorizationRules} bean is picked up by the slice as an ordinary
 *       {@code @Configuration}... it is not, so each service's slice test imports its own rules
 *       class alongside this one.
 *   <li>{@link JwtDecoder} - built from {@link TestTokens}' public key. In production four of the
 *       five services get theirs from Boot, built from customer-service's JWK set URL; a slice has
 *       no network and no JWKS server, so the test supplies one that verifies the tokens the test
 *       mints.
 *   <li>{@link ApiErrorResponder} - the 401 and 403 bodies. Needed explicitly because the
 *       auto-configuration that would normally provide it is not one a slice loads.
 *   <li>{@link SecurityMockMvcCustomizer} - lets {@code @WithMockUser} reach the filter chain.
 * </ul>
 */
@TestConfiguration
@Import({ResourceServerAutoConfiguration.class, SecurityMockMvcCustomizer.class})
public class SecurityTestConfiguration {

    @Bean
    public JwtDecoder jwtDecoder() {
        return TestTokens.decoder();
    }

    @Bean
    public ApiErrorResponder apiErrorResponder(ObjectMapper objectMapper) {
        return new ApiErrorResponder(objectMapper);
    }
}
