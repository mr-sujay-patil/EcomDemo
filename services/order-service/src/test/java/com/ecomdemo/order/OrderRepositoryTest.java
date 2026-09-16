package com.ecomdemo.order;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.ecomdemo.support.TestFixtures;

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

    /**
     * The product as it arrived from catalog-service. Not a row in this database and not an entity -
     * OrderItem copies the name and price out of it and keeps only the id.
     */
    private static final com.ecomdemo.order.client.CatalogProduct KEYBOARD =
            TestFixtures.product(1L, "Test Keyboard", "129.99");

    /** Orders belong to somebody, and every query here is scoped to that somebody. */
    private Long ownerId;

    @BeforeEach
    void chooseAnOwner() {
        // No users table to insert into: it belongs to customer-service. This id refers to nothing
        // and nothing checks it - which is exactly what the missing foreign key means.
        ownerId = 4242L;
    }

    private Order saveOrder(Instant placedAt, int quantity) {
        Order order = new Order(ownerId, placedAt);
        order.addItem(new OrderItem(order, KEYBOARD, quantity));
        return orderRepository.save(order);
    }

    @Test
    void findByIdAndCustomerWithItems_whenTheOrderExists_returnsItWithItemsAlreadyInitialised() {
        // GIVEN
        Long id = saveOrder(LATER, 2).getId();
        entityManager.flush();
        entityManager.clear();   // detach, so a broken join fetch cannot be masked by the cache

        // WHEN
        Optional<Order> found = orderRepository.findByIdAndCustomerWithItems(id, ownerId);

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
    void findByIdAndCustomerWithItems_whenTheOrderDoesNotExist_returnsEmpty() {
        // WHEN / THEN
        assertThat(orderRepository.findByIdAndCustomerWithItems(9999L, ownerId)).isEmpty();
    }

    @Test
    void findAllWithItems_withSeveralOrders_returnsNewestFirst() {
        // GIVEN - saved oldest first, so insertion order would give the wrong answer
        saveOrder(EARLIER, 1);
        saveOrder(LATER, 3);
        entityManager.flush();
        entityManager.clear();

        // WHEN
        List<Order> orders = orderRepository.findAllByCustomerWithItems(ownerId);

        // THEN - "order by o.placedAt desc" is doing the work, not chance
        assertThat(orders).hasSize(2);
        assertThat(orders).extracting(Order::getPlacedAt).containsExactly(LATER, EARLIER);
        assertThat(PERSISTENCE_UTIL.isLoaded(orders.getFirst(), "items")).isTrue();
    }

    @Test
    void findAllByCustomerWithItems_whenThereAreNoOrders_returnsEmptyList() {
        // WHEN / THEN
        assertThat(orderRepository.findAllByCustomerWithItems(ownerId)).isEmpty();
    }
}
