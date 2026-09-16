package com.ecomdemo.cart;

import java.util.Optional;

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
 * <p>Only {@code findByCustomerIdWithItems} is tested. Spring Data generates {@code findById},
 * {@code save} and the rest, and testing framework code we did not write would only assert that
 * Spring Data works.
 *
 * <p>Phase 20 made the setup dramatically shorter, which is worth noticing. It used to have to create
 * two Products and a Customer before it could create a cart, because three foreign keys demanded that
 * those rows exist. Those tables are in other services' databases now, so a cart is just a customer
 * id and its lines are just product ids - and the test can say what it means without building a
 * world first. That cuts both ways: the same absence is why nothing stops a cart line pointing at a
 * product that never existed.
 */
@DataJpaTest
@ActiveProfiles("test")
class CartRepositoryTest {

    private static final PersistenceUtil PERSISTENCE_UTIL = Persistence.getPersistenceUtil();

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private EntityManager entityManager;

    private Long ownerId;

    @BeforeEach
    void seedACartWithTwoLines() {
        // No Product rows and no Customer row to create first: both tables belong to other services.
        // The ids below refer to nothing this database can see, and nothing checks them.
        ownerId = 4242L;

        Cart cart = new Cart(ownerId);
        cart.addOrIncrease(1L, 2);
        cart.addOrIncrease(2L, 1);
        cartRepository.save(cart);

        // Flush the inserts, then detach everything. Without the clear(), the entities would still
        // be in the persistence context and the next query would return them from memory - the
        // test would pass even if the join fetch were broken.
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void findByCustomerIdWithItems_whenTheCartExists_returnsItemsAndProductsAlreadyInitialised() {
        // WHEN
        Optional<Cart> found = cartRepository.findByCustomerIdWithItems(ownerId);

        // THEN
        assertThat(found).isPresent();
        Cart cart = found.get();

        // The join fetch must have initialised the items. Checking isLoaded rather than simply
        // reading the values is the point: reading them would silently trigger a lazy load and pass
        // either way. This is what stops the N+1 problem regressing unnoticed.
        assertThat(PERSISTENCE_UTIL.isLoaded(cart, "items"))
                .as("cart.items should be fetched by the query, not lazily loaded")
                .isTrue();

        // There is no second level to check any more. The query used to join fetch i.product as
        // well, and that assertion went with it - the N+1 it prevented is now an HTTP call per line,
        // which no JPQL can fix and which CartServiceTest guards instead by asserting that the whole
        // cart is priced in one request.
        assertThat(cart.getItems()).hasSize(2);
        assertThat(cart.getItems()).extracting(CartItem::getProductId).containsExactly(1L, 2L);
    }

    @Test
    void findByCustomerIdWithItems_whenTheCartHasNoItems_stillReturnsTheCart() {
        // GIVEN - a left join, so an empty cart must not disappear from the result
        Long otherOwnerId = 4343L;
        cartRepository.save(new Cart(otherOwnerId));
        entityManager.flush();
        entityManager.clear();

        // WHEN
        Optional<Cart> found = cartRepository.findByCustomerIdWithItems(otherOwnerId);

        // THEN - an inner join would have returned empty here
        assertThat(found).isPresent();
        assertThat(found.get().isEmpty()).isTrue();
    }

    @Test
    void findByCustomerIdWithItems_whenTheCartDoesNotExist_returnsEmpty() {
        // WHEN / THEN
        assertThat(cartRepository.findByCustomerIdWithItems(9999L)).isEmpty();
    }
}
