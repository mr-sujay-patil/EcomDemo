package com.ecomdemo.order;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * One order and its lines in a single query - and only if it belongs to this customer.
     *
     * <p>The ownership check is in the WHERE clause rather than in a comparison afterwards. A query
     * that cannot return somebody else's order is a stronger guarantee than one that returns it and
     * relies on the next line of code to notice, and it is what lets the service answer 404 without
     * ever having loaded the row.
     */
    @Query("""
            select distinct o from Order o
            left join fetch o.items
            where o.id = :id and o.customerId = :customerId
            """)
    Optional<Order> findByIdAndCustomerWithItems(Long id, Long customerId);

    /** Same idea for the list endpoint: this customer's orders, newest first. */
    @Query("""
            select distinct o from Order o
            left join fetch o.items
            where o.customerId = :customerId
            order by o.placedAt desc, o.id desc
            """)
    List<Order> findAllByCustomerWithItems(Long customerId);
}
