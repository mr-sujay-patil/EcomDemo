package com.ecomdemo.order;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.ecomdemo.common.NotFoundException;
import com.ecomdemo.order.dto.OrderResponse;
import com.ecomdemo.product.Product;
import com.ecomdemo.support.TestFixtures;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * Unit tests for {@link OrderService}'s read methods. The checkout rules live in
 * {@code OrderPlacementTest}, alongside the class that implements them.
 *
 * <p>The retry is deliberately <em>not</em> tested here. {@code @Retryable} is applied by a proxy,
 * and a plain {@code new OrderService(...)} has no proxy - a retry test at this level would call the
 * bare method, observe no retry, and be measuring nothing. That is the cost of the declarative form:
 * the manual loop it replaced would have been testable with Mockito alone. {@code OrderRetryTest}
 * uses a real context instead, which also proves the annotation is switched on at all.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderPlacement orderPlacement;

    private OrderService orderService;

    private static final Long CUSTOMER_ID = 42L;

    @BeforeEach
    void setUp() {
        // A real OrderMetrics over an in-memory registry rather than a mock: it is a value-like
        // collaborator with no behaviour to arrange, and a mock here would only assert that this
        // test knows which methods the production code calls. What the meters actually record is
        // OrderMetricsTest's job.
        orderService = new OrderService(orderRepository, orderPlacement,
                new OrderMetrics(new SimpleMeterRegistry()));
    }

    private static final Instant NOW = Instant.parse("2026-09-16T10:15:30Z");

    /** Only the read tests need a real product on an order line. */
    private final Product keyboard = TestFixtures.product(1L, "Mechanical Keyboard", "129.99", 40);

    @Nested
    class FindAll {

        @Test
        void findAll_whenOrdersExist_returnsThemAsResponses() {
            // GIVEN
            Order order = TestFixtures.withId(new Order(TestFixtures.customer(CUSTOMER_ID), NOW), 1L);
            order.addItem(new OrderItem(order, keyboard, 2));
            given(orderRepository.findAllByCustomerWithItems(CUSTOMER_ID)).willReturn(List.of(order));

            // WHEN / THEN
            assertThat(orderService.findAll(CUSTOMER_ID)).singleElement().satisfies(response -> {
                assertThat(response.id()).isEqualTo(1L);
                assertThat(response.totalAmount()).isEqualByComparingTo("259.98");
            });
        }

        @Test
        void findAll_whenThereAreNoOrders_returnsEmptyList() {
            // GIVEN
            given(orderRepository.findAllByCustomerWithItems(CUSTOMER_ID)).willReturn(List.of());

            // WHEN / THEN
            assertThat(orderService.findAll(CUSTOMER_ID)).isEmpty();
        }
    }

    @Nested
    class FindById {

        @Test
        void findById_whenTheOrderExists_returnsIt() {
            // GIVEN
            Order order = TestFixtures.withId(new Order(TestFixtures.customer(CUSTOMER_ID), NOW), 5L);
            order.addItem(new OrderItem(order, keyboard, 1));
            given(orderRepository.findByIdAndCustomerWithItems(5L, CUSTOMER_ID)).willReturn(Optional.of(order));

            // WHEN / THEN
            OrderResponse found = orderService.findById(5L, CUSTOMER_ID);
            assertThat(found.id()).isEqualTo(5L);
            assertThat(found.placedAt()).isEqualTo(NOW);
            assertThat(found.items()).hasSize(1);
        }

        @Test
        void findById_whenTheOrderIsMissing_throwsNotFound() {
            // GIVEN
            given(orderRepository.findByIdAndCustomerWithItems(42L, CUSTOMER_ID)).willReturn(Optional.empty());

            // WHEN / THEN
            assertThatThrownBy(() -> orderService.findById(42L, CUSTOMER_ID))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Order 42 not found");
        }
    }
}
