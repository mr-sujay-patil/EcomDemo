package com.ecomdemo.product;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data generates the implementation at startup - findAll, findById, save, deleteById and the
 * rest come from {@link JpaRepository}. No @Repository annotation is needed; extending the
 * interface is enough for it to be detected.
 */
public interface ProductRepository extends JpaRepository<Product, Long> {
}
