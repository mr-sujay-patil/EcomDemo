package com.ecomdemo.support;

import com.ecomdemo.auth.JwtSecurityUserConverter;
import com.ecomdemo.common.ApiErrorResponder;
import com.ecomdemo.common.JwtConfiguration;
import com.ecomdemo.common.WebSecurityConfiguration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Everything a {@code @WebMvcTest} needs to exercise the real security rules.
 *
 * <p>A slice loads controllers, not {@code @Configuration} or {@code @Component} classes, so each of
 * these has to be asked for explicitly. Collected here so the list lives in one place: it has grown
 * twice already - once when the rules arrived in Phase 8, once when the JWT decoder did - and the
 * failure when a piece is missing is a context-load error whose message names the missing bean rather
 * than the test that needed it.
 *
 * <ul>
 *   <li>{@link WebSecurityConfiguration} - the authorization rules themselves. Without it the slice
 *       silently falls back to Boot's defaults and tests the wrong thing.
 *   <li>{@link JwtConfiguration} - the encoder and decoder. The filter chain now demands a
 *       {@code JwtDecoder}, and with no {@code JWT_SECRET} set it generates an ephemeral key, which
 *       is exactly right for a test.
 *   <li>{@link JwtSecurityUserConverter} - turns a verified token back into a {@code SecurityUser}.
 *   <li>{@link ApiErrorResponder} - the 401 and 403 bodies.
 *   <li>{@link SecurityMockMvcCustomizer} - lets {@code @WithMockUser} reach the filter chain.
 * </ul>
 */
@TestConfiguration
@Import({WebSecurityConfiguration.class,
        JwtConfiguration.class,
        JwtSecurityUserConverter.class,
        ApiErrorResponder.class,
        SecurityMockMvcCustomizer.class})
public class SecurityTestConfiguration {
}
