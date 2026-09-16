package com.ecomdemo.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.ecomdemo.common.ConflictException;
import com.ecomdemo.common.NotFoundException;
import com.ecomdemo.order.dto.OrderItemResponse;
import com.ecomdemo.order.dto.OrderResponse;

import com.ecomdemo.support.WithMockCustomer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import com.ecomdemo.common.ApiErrorResponder;
import com.ecomdemo.support.SecurityMockMvcCustomizer;
import com.ecomdemo.common.WebSecurityConfiguration;

import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.BIG_DECIMAL;
import static org.mockito.BDDMockito.given;

/** Slice tests for {@link OrderController}. */
@WebMvcTest(OrderController.class)
/*
 * A @WebMvcTest slice loads controllers, not arbitrary @Configuration classes - so without this the
 * real rules never load, Boot's default security applies instead, and @AuthenticationPrincipal is
 * not even resolved (Spring MVC falls back to treating SecurityUser as a model attribute and tries
 * to construct one). Importing them means these tests exercise the authorization rules that ship.
 */
@Import({WebSecurityConfiguration.class, ApiErrorResponder.class, SecurityMockMvcCustomizer.class})
class OrderControllerTest {

    /** Matches the id in @WithMockCustomer, so the stubs and the principal agree. */
    private static final Long CUSTOMER_ID = 42L;


    private static final Instant PLACED_AT = Instant.parse("2026-09-16T10:15:30Z");

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private OrderService orderService;

    private static OrderResponse anOrder() {
        return new OrderResponse(1L, PLACED_AT,
                List.of(new OrderItemResponse(1L, "Mechanical Keyboard",
                        new BigDecimal("129.99"), 2, new BigDecimal("259.98"))),
                new BigDecimal("259.98"));
    }

    @Test
    @WithMockCustomer(id = 42L)
    void place_withItemsInTheCart_returns201WithLocationHeader() {
        // GIVEN
        given(orderService.placeOrder(CUSTOMER_ID)).willReturn(anOrder());

        // WHEN / THEN - POST takes no body: the cart already holds everything the server needs
        assertThat(mvc.post().uri("/api/orders"))
                .hasStatus(HttpStatus.CREATED)
                .hasHeader("Location", "/api/orders/1")
                .bodyJson()
                .hasPathSatisfying("$.totalAmount",
                        total -> assertThat(total).convertTo(BIG_DECIMAL).isEqualByComparingTo("259.98"))
                .hasPathSatisfying("$.items[0].productName",
                        name -> assertThat(name).isEqualTo("Mechanical Keyboard"));
    }

    @Test
    @WithMockCustomer(id = 42L)
    void place_withAnEmptyCart_returns409() {
        // GIVEN
        given(orderService.placeOrder(CUSTOMER_ID))
                .willThrow(new ConflictException("Cannot place an order: the cart is empty"));

        // WHEN / THEN
        assertThat(mvc.post().uri("/api/orders"))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .hasPathSatisfying("$.status", s -> assertThat(s).isEqualTo(409))
                .hasPathSatisfying("$.message",
                        m -> assertThat(m).isEqualTo("Cannot place an order: the cart is empty"));
    }

    @Test
    @WithMockCustomer(id = 42L)
    void place_whenStockRanOut_returns409() {
        // GIVEN
        given(orderService.placeOrder(CUSTOMER_ID))
                .willThrow(new ConflictException("Only 0 unit(s) of '27\" 4K Monitor' in stock, ordered 1"));

        // WHEN / THEN
        assertThat(mvc.post().uri("/api/orders"))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .extractingPath("$.message").asString().contains("Only 0 unit(s)");
    }

    @Test
    @WithMockCustomer(id = 42L)
    void list_whenOrdersExist_returns200WithJsonArray() {
        // GIVEN
        given(orderService.findAll(CUSTOMER_ID)).willReturn(List.of(anOrder()));

        // WHEN / THEN
        assertThat(mvc.get().uri("/api/orders"))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$[0].id", id -> assertThat(id).isEqualTo(1))
                // Instant serialises as an ISO-8601 string, not a numeric timestamp
                .hasPathSatisfying("$[0].placedAt",
                        at -> assertThat(at).asString().isEqualTo("2026-09-16T10:15:30Z"));
    }

    @Test
    @WithMockCustomer(id = 42L)
    void list_whenThereAreNoOrders_returns200WithAnEmptyArray() {
        // GIVEN
        given(orderService.findAll(CUSTOMER_ID)).willReturn(List.of());

        // WHEN / THEN - an empty list, not a 404
        assertThat(mvc.get().uri("/api/orders"))
                .hasStatus(HttpStatus.OK)
                .bodyJson().extractingPath("$").asArray().isEmpty();
    }

    @Test
    @WithMockCustomer(id = 42L)
    void get_whenTheOrderExists_returns200() {
        // GIVEN
        given(orderService.findById(1L, CUSTOMER_ID)).willReturn(anOrder());

        // WHEN / THEN
        assertThat(mvc.get().uri("/api/orders/1"))
                .hasStatus(HttpStatus.OK)
                .bodyJson().extractingPath("$.id").isEqualTo(1);
    }

    @Test
    @WithMockCustomer(id = 42L)
    void get_whenTheOrderIsMissing_returns404() {
        // GIVEN
        given(orderService.findById(42L, CUSTOMER_ID)).willThrow(new NotFoundException("Order 42 not found"));

        // WHEN / THEN
        assertThat(mvc.get().uri("/api/orders/42"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.message").isEqualTo("Order 42 not found");
    }
}
