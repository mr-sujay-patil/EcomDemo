package com.ecomdemo.shared.testsupport;

import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * Makes {@code @WithMockUser} and {@code @WithMockCustomer} actually reach the filter chain.
 *
 * <p>The security filter chain here is stateless, so {@code SecurityContextHolderFilter} re-reads the
 * context from the request at the start of every request - discarding whatever the test annotation
 * put in the thread-local before it. {@code springSecurity()} inserts the test's context into the
 * request instead, which is where the filter looks. Without it, every authenticated slice test gets
 * a 401 that looks exactly like a broken authorization rule.
 *
 * <p>Registered as a {@link MockMvcBuilderCustomizer} rather than by building a
 * {@code MockMvcTester} by hand, because the auto-configured one also carries Boot's HTTP message
 * converters - and a hand-built one does not, which surfaces later as "No JSON message converter
 * available" from an assertion rather than from anything to do with security.
 */
@TestConfiguration
public class SecurityMockMvcCustomizer {

    @Bean
    public MockMvcBuilderCustomizer springSecurityCustomizer() {
        return builder -> builder.apply(springSecurity());
    }
}
