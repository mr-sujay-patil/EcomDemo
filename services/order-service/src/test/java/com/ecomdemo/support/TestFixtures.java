package com.ecomdemo.support;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.ecomdemo.order.client.CatalogProduct;

import org.springframework.test.util.ReflectionTestUtils;

/**
 * Builders for entities that need an id, and for the remote shapes this service consumes.
 *
 * <p>Ids are {@code @GeneratedValue} with no setter, so in production only Hibernate ever assigns
 * one. A unit test never touches Hibernate, so the field is set reflectively; keeping that compromise
 * in one place stops it leaking into every test.
 *
 * <p>Note what {@code product(...)} returns now: a {@link CatalogProduct}, which is a DTO that
 * arrived over HTTP, not a {@code Product} entity. There is no Product class in this service. That
 * one changed return type is the whole of the split as a test author experiences it - the thing a
 * checkout works with is a snapshot somebody sent, not a row it can reach.
 */
public final class TestFixtures {

    private TestFixtures() {
    }

    /** Assigns an id to an already-built entity, mimicking what {@code save()} would return. */
    public static <T> T withId(T entity, Long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    /** A catalogue product as catalog-service would have returned it. */
    public static CatalogProduct product(Long id, String name, String price) {
        return new CatalogProduct(id, name, money(price));
    }

    /** A product with sensible defaults, for tests that care only about the id. */
    public static CatalogProduct product(Long id) {
        return product(id, "Product " + id, "10.00");
    }

    public static BigDecimal money(String amount) {
        return new BigDecimal(amount).setScale(2, RoundingMode.HALF_UP);
    }
}
