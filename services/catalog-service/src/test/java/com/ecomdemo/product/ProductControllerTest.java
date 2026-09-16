package com.ecomdemo.product;

import java.math.BigDecimal;
import java.util.List;

import com.ecomdemo.shared.NotFoundException;
import com.ecomdemo.product.dto.ProductRequest;
import com.ecomdemo.product.dto.ProductResponse;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.security.test.context.support.WithMockUser;

import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import com.ecomdemo.shared.testsupport.SecurityTestConfiguration;

import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.BIG_DECIMAL;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Slice tests for {@link ProductController}.
 *
 * <p>{@code @WebMvcTest} loads only the web layer: this controller, Jackson, Bean Validation and
 * {@code GlobalExceptionHandler}. There is no database, no Hibernate and no real service — the
 * service is replaced by a {@code @MockitoBean}. So a failure here means the HTTP contract broke:
 * a wrong status code, a wrong JSON shape, or an exception that no longer maps where it should.
 *
 * <p>{@code @MockitoBean} is the Spring Boot 4 replacement for the removed {@code @MockBean}.
 *
 * <p>Reads carry no authentication at all, which is the assertion: browsing the catalogue is public.
 * Writes carry {@code @WithMockUser(roles = "ADMIN")} - plain, because these tests care only about
 * the role and nothing here needs a customer id. {@code SecurityRulesTest} covers what happens when
 * the role is wrong or missing.
 */
@WebMvcTest(ProductController.class)
/*
 * A @WebMvcTest slice loads controllers, not arbitrary @Configuration classes, and not
 * auto-configurations beyond a short web-related list - so without these imports the real rules
 * never load, Boot's default security applies instead, and @AuthenticationPrincipal is not even
 * resolved (Spring MVC falls back to treating SecurityUser as a model attribute and tries to
 * construct one).
 *
 * TWO imports since Phase 20, and the second is easy to forget. SecurityTestConfiguration brings the
 * filter chain, which is shared by all five services; CatalogServiceAuthorizationRules brings the
 * permitAll for GET /api/products, which is this service's alone. Without the second, the shared
 * chain still loads and still works - it simply has no rule making anything public, so every
 * anonymous read falls through to anyRequest().authenticated() and returns 401. The failure looks
 * exactly like a broken authorization rule rather than a missing import, which is why it is worth a
 * comment.
 */
@Import({SecurityTestConfiguration.class, CatalogServiceAuthorizationRules.class})
class ProductControllerTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ProductService productService;

    private static ProductResponse keyboard() {
        return new ProductResponse(1L, "Mechanical Keyboard", "Hot-swappable", new BigDecimal("129.99"), "Peripherals");
    }

    @Test
    void list_whenProductsExist_returns200WithJsonArray() {
        // GIVEN
        given(productService.findAll()).willReturn(List.of(keyboard()));

        // WHEN / THEN
        assertThat(mvc.get().uri("/api/products"))
                .hasStatus(HttpStatus.OK)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .bodyJson()
                .extractingPath("$[0].name").isEqualTo("Mechanical Keyboard");
    }

    @Test
    void get_whenTheProductExists_returns200WithTheProduct() {
        // GIVEN
        given(productService.findById(1L)).willReturn(keyboard());

        // WHEN / THEN
        assertThat(mvc.get().uri("/api/products/1"))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.id", id -> assertThat(id).isEqualTo(1))
                // money crosses the wire as a JSON number; convert before comparing so the
                // assertion is about the value, not about how JsonPath happened to parse it
                .hasPathSatisfying("$.price",
                        price -> assertThat(price).convertTo(BIG_DECIMAL).isEqualByComparingTo("129.99"))
                // No stockQuantity here any more, and its absence is the assertion: availability is
                // inventory-service's answer to give, and this response is served from a cache that
                // must never hold a number a checkout depends on.
                .doesNotHavePath("$.stockQuantity");
    }

    @Test
    void get_whenTheProductIsMissing_returns404InTheSharedErrorShape() {
        // GIVEN - the service throws; the advice, not the controller, decides the status
        given(productService.findById(9999L)).willThrow(new NotFoundException("Product 9999 not found"));

        // WHEN / THEN
        assertThat(mvc.get().uri("/api/products/9999"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .hasPathSatisfying("$.status", status -> assertThat(status).isEqualTo(404))
                .hasPathSatisfying("$.message",
                        message -> assertThat(message).isEqualTo("Product 9999 not found"));
    }

    @Test
    void get_withANonNumericId_returns400() {
        // WHEN / THEN - MethodArgumentTypeMismatchException, mapped by the advice
        assertThat(mvc.get().uri("/api/products/abc"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.message").asString().contains("'id'");

        verify(productService, never()).findById(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_withAValidBody_returns201WithLocationHeader() {
        // GIVEN
        given(productService.create(any(ProductRequest.class))).willReturn(keyboard());

        // WHEN / THEN
        assertThat(mvc.post().uri("/api/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Mechanical Keyboard","description":"Hot-swappable",
                         "price":129.99}"""))
                .hasStatus(HttpStatus.CREATED)
                .hasHeader("Location", "/api/products/1");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_withABlankNameAndNegativePrice_returns400NamingBothFields() {
        // WHEN - @Valid rejects this before the controller body ever runs
        assertThat(mvc.post().uri("/api/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"","price":-1}"""))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.message").asString()
                // every violated constraint is reported, so one round trip fixes the whole payload
                .contains("name must not be blank")
                .contains("price must be greater than zero");

        // THEN - the service was never reached
        verify(productService, never()).create(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_withMalformedJson_returns400() {
        // WHEN / THEN
        assertThat(mvc.post().uri("/api/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{oops"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.message").isEqualTo("Malformed or unreadable request body");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void update_withAValidBody_returns200() {
        // GIVEN
        given(productService.update(eq(1L), any(ProductRequest.class))).willReturn(keyboard());

        // WHEN / THEN
        assertThat(mvc.put().uri("/api/products/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Mechanical Keyboard","price":129.99}"""))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .extractingPath("$.name").isEqualTo("Mechanical Keyboard");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_whenTheProductExists_returns204WithNoBody() {
        // WHEN / THEN
        assertThat(mvc.delete().uri("/api/products/1"))
                .hasStatus(HttpStatus.NO_CONTENT)
                .body().isEmpty();

        verify(productService).delete(1L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_whenTheProductIsMissing_returns404() {
        // GIVEN
        willThrow(new NotFoundException("Product 9999 not found")).given(productService).delete(9999L);

        // WHEN / THEN
        assertThat(mvc.delete().uri("/api/products/9999"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .extractingPath("$.status").isEqualTo(404);
    }
}
