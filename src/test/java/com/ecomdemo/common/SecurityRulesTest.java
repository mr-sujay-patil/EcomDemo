package com.ecomdemo.common;

import java.math.BigDecimal;
import java.util.List;

import com.ecomdemo.cart.CartController;
import com.ecomdemo.cart.CartService;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.customer.CustomerController;
import com.ecomdemo.customer.CustomerService;
import com.ecomdemo.customer.dto.CustomerResponse;
import com.ecomdemo.order.OrderController;
import com.ecomdemo.order.OrderService;
import com.ecomdemo.product.ProductController;
import com.ecomdemo.product.ProductService;
import com.ecomdemo.product.dto.ProductResponse;
import com.ecomdemo.support.WithMockCustomer;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import com.ecomdemo.support.SecurityTestConfiguration;

import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * The authorization matrix: for every rule, who is allowed and who is refused.
 *
 * <p>This is the test the phase asks for, and it is deliberately separate from the controller tests.
 * Those answer "does this endpoint behave correctly for someone entitled to call it"; this one answers
 * "who is entitled", which is a different question with a different failure mode - a broken rule here
 * does not make an endpoint wrong, it makes it available to the wrong people, and every other test in
 * the suite would still pass.
 *
 * <p>Every service is mocked. A 200 here means the request reached the controller, nothing more; what
 * it would have done is covered elsewhere. That is the point - the subject is the filter chain.
 */
@WebMvcTest(controllers = {ProductController.class, CartController.class,
        OrderController.class, CustomerController.class})
@Import(SecurityTestConfiguration.class)
class SecurityRulesTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private CartService cartService;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private CustomerService customerService;

    private static final String PRODUCT_JSON = """
            {"name":"Thing","description":"d","price":10.00,"stockQuantity":1}""";

    @Nested
    class ProductReadsArePublic {

        @Test
        void listProducts_withNoCredentials_isAllowed() {
            // GIVEN nobody logged in
            given(productService.findAll()).willReturn(List.of());

            // WHEN / THEN browsing the catalogue needs no account at all
            assertThat(mvc.get().uri("/api/products")).hasStatus(HttpStatus.OK);
        }

        @Test
        void getProduct_withNoCredentials_isAllowed() {
            // GIVEN
            given(productService.findById(anyLong())).willReturn(
                    new ProductResponse(1L, "Thing", "d", new BigDecimal("10.00"), 1, null));

            // WHEN / THEN
            assertThat(mvc.get().uri("/api/products/1")).hasStatus(HttpStatus.OK);
        }
    }

    @Nested
    class ProductWritesRequireAdmin {

        @Test
        void createProduct_withNoCredentials_is401() {
            // GIVEN nobody logged in
            // WHEN / THEN 401, not 403: the server does not know who this is
            assertThat(mvc.post().uri("/api/products")
                    .contentType(MediaType.APPLICATION_JSON).content(PRODUCT_JSON))
                    .hasStatus(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @WithMockCustomer(id = 7L)
        void createProduct_asACustomer_is403() {
            // GIVEN a shopper
            // WHEN / THEN 403, not 401: the server knows exactly who this is, and the answer is still no
            assertThat(mvc.post().uri("/api/products")
                    .contentType(MediaType.APPLICATION_JSON).content(PRODUCT_JSON))
                    .hasStatus(HttpStatus.FORBIDDEN);
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void createProduct_asAnAdmin_isAllowed() {
            // GIVEN an administrator
            given(productService.create(any())).willReturn(
                    new ProductResponse(1L, "Thing", "d", new BigDecimal("10.00"), 1, null));

            // WHEN / THEN
            assertThat(mvc.post().uri("/api/products")
                    .contentType(MediaType.APPLICATION_JSON).content(PRODUCT_JSON))
                    .hasStatus(HttpStatus.CREATED);
        }

        @Test
        @WithMockCustomer(id = 7L)
        void deleteProduct_asACustomer_is403() {
            // WHEN / THEN every write is covered by the same rule, not just POST
            assertThat(mvc.delete().uri("/api/products/1")).hasStatus(HttpStatus.FORBIDDEN);
        }
    }

    @Nested
    class CartAndOrdersRequireCustomer {

        @Test
        void viewCart_withNoCredentials_is401() {
            assertThat(mvc.get().uri("/api/cart")).hasStatus(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void viewCart_asAnAdmin_is403() {
            // An administrator is not a shopper. A role is a job, not a rank - ADMIN does not include
            // CUSTOMER, and an administrator has no cart to look at.
            assertThat(mvc.get().uri("/api/cart")).hasStatus(HttpStatus.FORBIDDEN);
        }

        @Test
        @WithMockCustomer(id = 7L)
        void viewCart_asACustomer_isAllowed() {
            given(cartService.getCart(7L)).willReturn(
                    new CartResponse(1L, List.of(), 0, BigDecimal.ZERO));

            assertThat(mvc.get().uri("/api/cart")).hasStatus(HttpStatus.OK);
        }

        @Test
        void listOrders_withNoCredentials_is401() {
            assertThat(mvc.get().uri("/api/orders")).hasStatus(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void placeOrder_asAnAdmin_is403() {
            assertThat(mvc.post().uri("/api/orders")).hasStatus(HttpStatus.FORBIDDEN);
        }

        @Test
        @WithMockCustomer(id = 7L)
        void listOrders_asACustomer_isAllowed() {
            given(orderService.findAll(7L)).willReturn(List.of());

            assertThat(mvc.get().uri("/api/orders")).hasStatus(HttpStatus.OK);
        }
    }

    @Nested
    class RegistrationIsPublicAndProfileIsNot {

        @Test
        void register_withNoCredentials_isAllowed() {
            // GIVEN nobody logged in - you cannot be required to authenticate in order to sign up
            given(customerService.register(any())).willReturn(
                    new CustomerResponse(1L, "new@ecomdemo.local", "New", "CUSTOMER", null));

            assertThat(mvc.post().uri("/api/customers/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"email":"new@ecomdemo.local","password":"password123","displayName":"New"}"""))
                    .hasStatus(HttpStatus.CREATED);
        }

        @Test
        void profile_withNoCredentials_is401() {
            assertThat(mvc.get().uri("/api/customers/me")).hasStatus(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @WithMockCustomer(id = 7L)
        void profile_asACustomer_returnsTheirOwnRecord() {
            // GIVEN a shopper asking for "me"
            given(customerService.findById(7L)).willReturn(
                    new CustomerResponse(7L, "them@ecomdemo.local", "Them", "CUSTOMER", null));

            // WHEN / THEN the id comes from the principal - there is no URL a caller could change to
            // read somebody else's profile
            assertThat(mvc.get().uri("/api/customers/me")).hasStatus(HttpStatus.OK)
                    .bodyJson().extractingPath("$.id").isEqualTo(7);
        }
    }

    @Nested
    class ErrorsKeepTheStandardShape {

        @Test
        void unauthenticated_returnsTheSameJsonShapeAsEveryOtherError() {
            // WHEN / THEN a client parsing {status, message} gets one here too, rather than the empty
            // body Spring Security would send on its own
            assertThat(mvc.get().uri("/api/cart"))
                    .hasStatus(HttpStatus.UNAUTHORIZED)
                    .bodyJson().extractingPath("$.status").isEqualTo(401);
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void forbidden_returnsTheSameJsonShapeAsEveryOtherError() {
            assertThat(mvc.get().uri("/api/cart"))
                    .hasStatus(HttpStatus.FORBIDDEN)
                    .bodyJson().extractingPath("$.message").asString()
                    .contains("permission");
        }
    }
}
