package com.ecomdemo.customer;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    /** The lookup behind every authentication: HTTP Basic sends an email, this turns it into a user. */
    Optional<Customer> findByEmail(String email);

    boolean existsByEmail(String email);
}
