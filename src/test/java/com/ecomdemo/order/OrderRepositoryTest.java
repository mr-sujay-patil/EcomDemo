package com.ecomdemo.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.ecomdemo.product.Product;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Persistence;
import jakarta.persistence.PersistenceUtil;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository tests for the hand-written JPQL on {@link OrderRepository}: the join fetch and the
 * ordering, neither of which Spring Data would give us for free.
 */
@DataJpaTest
@ActiveProfiles("test")
class OrderRepositoryTest {

    private static final PersistenceUtil PERSISTENCE_UTIL = Persistence.getPersistenceUtil();
    private static final Instant EARLIER = Instant.parse("2026-09-15T09:00:00Z");
    private static final Instant LATER = Instant.parse("2026-09-16T10:15:30Z");

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private EntityManager entityManager;

    private Product keyboard;

    @BeforeEach
    void seedAProduct() {
        keyboard = entityManager.merge(
                new Product("Test Keyboard", "For the test", new BigDecimal("129.99"), 40));
    }

    private Order saveOrder(Instant placedAt, int quantity) {
        Order order = new Order(placedAt);
        order.addItem(new OrderItem(order, keyboard, quantity));
        return orderRepository.save(order);
    }

    @Test
    void findByIdWithItems_whenTheOrderExists_returnsItWithItemsAlreadyInitialised() {
        // GIVEN
        Long id = saveOrder(LATER, 2).getId();
        entityManager.flush();
        entityManager.clear();   // detach, so a broken join fetch cannot be masked by the cache

        // WHEN
        Optional<Order> found = orderRepository.findByIdWithItems(id);

        // THEN
        assertThat(found).isPresent();
        assertThat(PERSISTENCE_UTIL.isLoaded(found.get(), "items"))
                .as("order.items should be fetched by the query, not lazily loaded")
                .isTrue();
        assertThat(found.get().getItems()).singleElement().satisfies(item -> {
            assertThat(item.getProductName()).isEqualTo("Test Keyboard");
            assertThat(item.getUnitPrice()).isEqualByComparingTo("129.99");
            assertThat(item.getQuantity()).isEqualTo(2);
        });
        assertThat(found.get().getTotalAmount()).isEqualByComparingTo("259.98");
    }

    @Test
    void findByIdWithItems_whenTheOrderDoesNotExist_returnsEmpty() {
        // WHEN / THEN
        assertThat(orderRepository.findByIdWithItems(9999L)).isEmpty();
    }

    @Test
    void findAllWithItems_withSeveralOrders_returnsNewestFirst() {
        // GIVEN - saved oldest first, so insertion order would give the wrong answer
        saveOrder(EARLIER, 1);
        saveOrder(LATER, 3);
        entityManager.flush();
        entityManager.clear();

        // WHEN
        List<Order> orders = orderRepository.findAllWithItems();

        // THEN - "order by o.placedAt desc" is doing the work, not chance
        assertThat(orders).hasSize(2);
        assertThat(orders).extracting(Order::getPlacedAt).containsExactly(LATER, EARLIER);
        assertThat(PERSISTENCE_UTIL.isLoaded(orders.getFirst(), "items")).isTrue();
    }

    @Test
    void findAllWithItems_whenThereAreNoOrders_returnsEmptyList() {
        // WHEN / THEN
        assertThat(orderRepository.findAllWithItems()).isEmpty();
    }
}
