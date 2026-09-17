package com.ecomdemo.cart;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CartRepository extends JpaRepository<Cart, Long> {

    /**
     * Loads one customer's cart together with its items in a single query.
     *
     * <p>Without the join fetch, rendering a cart of N items costs 1 query for the cart and 1 for the
     * items - the N+1 select problem. {@code left join fetch} tells Hibernate to initialise that lazy
     * association eagerly for this query only.
     *
     * <p>Phase 20 removed a second {@code left join fetch i.product} from this query. The N+1 it
     * prevented has not gone away; it has changed kind. What used to be N extra SELECTs against the
     * same database would now be N HTTP calls to catalog-service, which no JPQL can fix - so
     * {@code CartService} fetches prices for the whole cart in one call instead. The database-level
     * N+1 and the service-level one are the same mistake at two scales, and only one of them has a
     * fix that lives in a repository.
     */
    @Query("""
            select distinct c from Cart c
            left join fetch c.items i
            where c.customerId = :customerId
            """)
    Optional<Cart> findByCustomerIdWithItems(Long customerId);
}
