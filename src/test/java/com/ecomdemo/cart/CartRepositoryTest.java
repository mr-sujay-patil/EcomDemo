package com.ecomdemo.cart;

import java.math.BigDecimal;
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
 * Repository tests for the hand-written JPQL on {@link CartRepository}.
 *
 * <p>{@code @DataJpaTest} loads Hibernate, the repositories and an embedded database — but no
 * controllers and no services. Each test runs in a transaction that is <strong>rolled back</strong>
 * afterwards, so tests cannot leak state into one another.
 *
 * <p>Only {@code findByIdWithItems} is tested. Spring Data generates {@code findById}, {@code save}
 * and the rest, and testing framework code we did not write would only assert that Spring Data
 * works.
 */
@DataJpaTest
@ActiveProfiles("test")
class CartRepositoryTest {

    private static final PersistenceUtil PERSISTENCE_UTIL = Persistence.getPersistenceUtil();

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private EntityManager entityManager;

    private Long cartId;

    @BeforeEach
    void seedACartWithTwoLines() {
        // data.sql already seeds cart id 1, so this test builds its own at id 2 rather than
        // depending on fixture data it does not control.
        Product keyboard = entityManager.merge(
                new Product("Test Keyboard", "For the test", new BigDecimal("129.99"), 40));
        Product mouse = entityManager.merge(
                new Product("Test Mouse", "For the test", new BigDecimal("49.50"), 120));

        Cart cart = new Cart(2L);
        cart.addOrIncrease(keyboard, 2);
        cart.addOrIncrease(mouse, 1);
        cartId = cartRepository.save(cart).getId();

        // Flush the inserts, then detach everything. Without the clear(), the entities would still
        // be in the persistence context and the next query would return them from memory - the
        // test would pass even if the join fetch were broken.
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void findByIdWithItems_whenTheCartExists_returnsItemsAndProductsAlreadyInitialised() {
        // WHEN
        Optional<Cart> found = cartRepository.findByIdWithItems(cartId);

        // THEN
        assertThat(found).isPresent();
        Cart cart = found.get();

        // The join fetch must have initialised both levels of the graph. Checking isLoaded rather
        // than simply reading the values is the point: reading them would silently trigger a lazy
        // load and pass either way. This is what stops the N+1 problem regressing unnoticed.
        assertThat(PERSISTENCE_UTIL.isLoaded(cart, "items"))
                .as("cart.items should be fetched by the query, not lazily loaded")
                .isTrue();
        assertThat(cart.getItems())
                .allSatisfy(item -> assertThat(PERSISTENCE_UTIL.isLoaded(item, "product"))
                        .as("cartItem.product should be fetched by the query")
                        .isTrue());

        assertThat(cart.getItems()).hasSize(2);
        assertThat(cart.total()).isEqualByComparingTo("309.48");
    }

    @Test
    void findByIdWithItems_whenTheCartHasNoItems_stillReturnsTheCart() {
        // GIVEN - a left join, so an empty cart must not disappear from the result
        cartRepository.save(new Cart(3L));
        entityManager.flush();
        entityManager.clear();

        // WHEN
        Optional<Cart> found = cartRepository.findByIdWithItems(3L);

        // THEN - an inner join would have returned empty here
        assertThat(found).isPresent();
        assertThat(found.get().isEmpty()).isTrue();
    }

    @Test
    void findByIdWithItems_whenTheCartDoesNotExist_returnsEmpty() {
        // WHEN / THEN
        assertThat(cartRepository.findByIdWithItems(9999L)).isEmpty();
    }
}
