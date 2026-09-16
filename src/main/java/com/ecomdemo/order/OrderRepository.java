package com.ecomdemo.order;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /** Single-query load of one order and its lines, avoiding an extra select per line. */
    @Query("""
            select distinct o from Order o
            left join fetch o.items
            where o.id = :id
            """)
    Optional<Order> findByIdWithItems(Long id);

    /** Same idea for the list endpoint, newest order first. */
    @Query("""
            select distinct o from Order o
            left join fetch o.items
            order by o.placedAt desc, o.id desc
            """)
    List<Order> findAllWithItems();
}
