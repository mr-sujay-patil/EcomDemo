package com.ecomdemo.support;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.ecomdemo.product.Product;

import org.springframework.test.util.ReflectionTestUtils;

/**
 * Builders for entities that need an id.
 *
 * <p>{@code Product.id} is {@code @GeneratedValue} with no setter, so in production only Hibernate
 * ever assigns one. A unit test never touches Hibernate — but {@code Cart.findItemFor} compares
 * {@code item.getProduct().getId()}, which would throw a {@link NullPointerException} against an
 * id-less product. Setting the field reflectively is the pragmatic fix; keeping it in one place
 * stops that compromise leaking into every test.
 *
 * <p>Repository tests do <em>not</em> use these builders: there, persisting the entity is the point,
 * and letting the database assign the id is part of what is under test.
 */
public final class TestFixtures {

    private TestFixtures() {
    }

    /** A product with a known id, priced to two decimal places like the service would store it. */
    public static Product product(Long id, String name, String price, int stockQuantity) {
        Product product = new Product(
                name,
                name + " description",
                new BigDecimal(price).setScale(2, RoundingMode.HALF_UP),
                stockQuantity);
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }

    /** A product with sensible defaults, for tests that care only about the id. */
    public static Product product(Long id) {
        return product(id, "Product " + id, "10.00", 100);
    }

    /** Assigns an id to an already-built entity, mimicking what {@code save()} would return. */
    public static <T> T withId(T entity, Long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    public static BigDecimal money(String amount) {
        return new BigDecimal(amount).setScale(2, RoundingMode.HALF_UP);
    }
}
