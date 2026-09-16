package com.ecomdemo.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.ecomdemo.common.ConflictException;
import com.ecomdemo.order.dto.OrderResponse;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The retry, tested through the proxy that actually implements it.
 *
 * <p>A {@code @SpringBootTest} for four small assertions looks heavy-handed until you try the
 * alternative: {@code @Retryable} is woven in by a bean post-processor, so a plain
 * {@code new OrderService(...)} retries nothing and a Mockito-only test would quietly assert that
 * one call happened once. This test fails if {@code @EnableResilientMethods} is ever removed from
 * {@code ResilienceConfiguration}, which is the failure most worth catching - the annotation stays,
 * the retry silently stops.
 *
 * <p>{@code OrderPlacement} is replaced with a mock so the outcome of an attempt can be dictated.
 * The context is otherwise real, so the proxy, its ordering against the transaction advice and the
 * retry policy are all the production ones.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class OrderRetryTest {

    @Autowired
    private OrderService orderService;

    @MockitoBean
    private OrderPlacement orderPlacement;

    private static OrderResponse response(long id) {
        return new OrderResponse(id, Instant.parse("2026-09-16T10:15:30Z"), List.of(),
                new BigDecimal("0.00"));
    }

    @Test
    void placeOrder_whenPlacementSucceeds_attemptsOnce() {
        // GIVEN
        given(orderPlacement.placeOnce()).willReturn(response(1L));

        // WHEN
        OrderResponse order = orderService.placeOrder();

        // THEN there is nothing to retry, so nothing is retried
        assertThat(order.id()).isEqualTo(1L);
        verify(orderPlacement, times(1)).placeOnce();
    }

    @Test
    void placeOrder_whenTheFirstAttemptHitsAVersionConflict_retriesAndSucceeds() {
        // GIVEN the first attempt loses the race and the second wins it
        given(orderPlacement.placeOnce())
                .willThrow(new OptimisticLockingFailureException("product changed"))
                .willReturn(response(2L));

        // WHEN
        OrderResponse order = orderService.placeOrder();

        // THEN the caller never learns there was a conflict at all
        assertThat(order.id()).isEqualTo(2L);
        verify(orderPlacement, times(2)).placeOnce();
    }

    @Test
    void placeOrder_whenEveryAttemptHitsAVersionConflict_givesUpAfterTheLimit() {
        // GIVEN contention that never clears
        willThrow(new OptimisticLockingFailureException("product changed"))
                .given(orderPlacement).placeOnce();

        // WHEN / THEN the exception escapes and GlobalExceptionHandler turns it into a 409
        assertThatThrownBy(() -> orderService.placeOrder())
                .isInstanceOf(OptimisticLockingFailureException.class);

        // AND it stopped. Retrying an endpoint forever under contention is how one slow request
        // becomes an outage - the honest answer to the caller is "someone else got there first".
        verify(orderPlacement, times((int) OrderService.MAX_RETRIES + 1)).placeOnce();
    }

    @Test
    void placeOrder_whenTheCartIsEmpty_doesNotRetry() {
        // GIVEN a failure no amount of retrying can fix
        willThrow(new ConflictException("Cannot place an order: the cart is empty"))
                .given(orderPlacement).placeOnce();

        // WHEN / THEN
        assertThatThrownBy(() -> orderService.placeOrder()).isInstanceOf(ConflictException.class);

        // AND it was attempted exactly once. @Retryable names the exceptions worth retrying; an
        // empty cart is not one, and retrying it would only make the caller wait longer for the
        // same 409.
        verify(orderPlacement, times(1)).placeOnce();
    }
}
