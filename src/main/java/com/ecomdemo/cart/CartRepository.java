package com.ecomdemo.cart;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CartRepository extends JpaRepository<Cart, Long> {

    /**
     * Loads one customer's cart together with its items and products in a single query.
     *
     * <p>Without the join fetch, rendering a cart of N items costs 1 query for the cart, 1 for the
     * items and N for the products - the N+1 select problem. {@code left join fetch} tells Hibernate
     * to initialise those lazy associations eagerly for this query only.
     */
    @Query("""
            select distinct c from Cart c
            left join fetch c.items i
            left join fetch i.product
            where c.customer.id = :customerId
            """)
    Optional<Cart> findByCustomerIdWithItems(Long customerId);
}
