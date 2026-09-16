package com.ecomdemo.inventory;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StockRepository extends JpaRepository<StockLevel, Long> {

    /**
     * Every stock row for a set of products, in one query.
     *
     * <p>A reservation covers a whole cart, so the alternative is one SELECT per line - the N+1 that
     * looks harmless at two lines and is not at twenty. Spring Data derives this from the method
     * name; {@code findAllById} would do the same job, and this spelling says at the call site that
     * the argument is a list of product ids rather than of stock ids, which here happen to be the
     * same numbers and are not the same thing.
     */
    List<StockLevel> findAllByProductIdIn(List<Long> productIds);
}
