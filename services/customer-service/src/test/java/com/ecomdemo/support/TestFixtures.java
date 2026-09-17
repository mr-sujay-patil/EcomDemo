package com.ecomdemo.support;

import java.time.Instant;

import com.ecomdemo.customer.Customer;
import com.ecomdemo.shared.security.Role;

import org.springframework.test.util.ReflectionTestUtils;

/**
 * Builders for entities that need an id.
 *
 * <p>{@code Customer.id} is {@code @GeneratedValue} with no setter, so in production only Hibernate
 * ever assigns one. A unit test never touches Hibernate, so the field is set reflectively; keeping
 * that compromise in one place stops it leaking into every test.
 *
 * <p>This used to be one class shared by the whole application, holding products and customers
 * together. It is per service now, and deliberately not in shared-kernel's test-jar: a fixture is a
 * builder for <em>an entity</em>, and an entity belongs to exactly one service. A shared fixture
 * class would be a shared domain model wearing a test costume.
 */
public final class TestFixtures {

    private TestFixtures() {
    }

    /** Assigns an id to an already-built entity, mimicking what {@code save()} would return. */
    public static <T> T withId(T entity, Long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    public static Customer customer(Long id, String email) {
        return withId(new Customer(email, "irrelevant-hash", "Test Customer",
                Role.CUSTOMER, Instant.parse("2026-01-01T00:00:00Z")), id);
    }

    public static Customer customer(Long id) {
        return customer(id, "customer" + id + "@ecomdemo.local");
    }
}
