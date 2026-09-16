package com.ecomdemo.product;

import java.time.Duration;
import java.util.List;

import com.ecomdemo.product.dto.ProductResponse;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import tools.jackson.databind.JavaType;
import tools.jackson.databind.type.TypeFactory;

/**
 * What is cached, how it is stored, and for how long.
 *
 * <h2>Cache-aside</h2>
 *
 * Spring's {@code @Cacheable} implements the cache-aside pattern: look in the cache, and on a miss
 * call the method and store what it returns. The application stays the thing that knows how to
 * produce the value - Redis never reads the database itself - which is why losing Redis entirely
 * degrades performance rather than correctness.
 *
 * <h2>JSON, not Java serialization</h2>
 *
 * Java serialization would be less code and is what Spring uses by default. It is avoided for three
 * reasons: the bytes are unreadable, so {@code redis-cli GET} tells you nothing when something looks
 * wrong; every cached class becomes part of a binary contract, so adding a field can make existing
 * entries undeserialisable; and deserialising untrusted bytes into arbitrary Java objects is a
 * remote-code-execution primitive.
 *
 * <h2>One known type per cache, rather than polymorphic typing</h2>
 *
 * The usual recipe is a generic serializer that records each value's class in the document so it can
 * rebuild anything. It works, but the cache will then instantiate whatever class a document names -
 * the same gadget problem as Java serialization in friendlier clothing - so it has to be fenced off
 * with a type validator.
 *
 * <p>None of that is needed here. Each cache holds exactly one shape: {@code products} a
 * {@code ProductResponse}, {@code product-list} a {@code List<ProductResponse>}. Saying so up front
 * means no type names in the JSON, nothing to validate, and documents that read like the API
 * responses they came from.
 *
 * <p>The price is that a new cache must declare its type here. That is a small and loud cost; the
 * alternative fails at runtime, on a read, far from this file.
 */
@Configuration
@EnableCaching
public class CacheConfiguration {

    /** One product, keyed by id. */
    public static final String PRODUCTS = "products";

    /** The whole catalogue, under a single key. */
    public static final String PRODUCT_LIST = "product-list";

    /**
     * The one key in {@link #PRODUCT_LIST}, named explicitly rather than derived.
     *
     * <p>Two reasons. It reads better - {@code product-list::all} instead of Spring's default
     * {@code product-list::SimpleKey []}, which is what a no-argument method produces and is an
     * unpleasant thing to meet in {@code redis-cli}.
     *
     * <p>And it lets every eviction be a targeted {@code evict(key)} rather than
     * {@code allEntries = true}. That matters here: measured against Redis in this setup,
     * {@code RedisCache.clear()} left the entry in place while {@code evict(key)} removed it, so a
     * cache configured with {@code allEntries = true} silently never invalidated - the failure mode
     * being stale data rather than an error. A single named key needs no wildcard sweep at all.
     */
    public static final String WHOLE_LIST_KEY = "'all'";

    /**
     * Ten minutes for a single product: individual products change rarely, and every write path
     * evicts the entry anyway, so the TTL is a safety net rather than the primary mechanism.
     */
    private static final Duration PRODUCT_TTL = Duration.ofMinutes(10);

    /**
     * Two minutes for the catalogue, deliberately shorter. It is one key covering every product, so
     * it is the entry most likely to be subtly wrong - any product created, changed or deleted
     * invalidates it, and a shorter life bounds the damage of an eviction anyone forgets to add.
     */
    private static final Duration PRODUCT_LIST_TTL = Duration.ofMinutes(2);

    /**
     * The default for any cache that does not name its own TTL.
     *
     * <p>A TTL is not optional. Without one, an entry whose eviction is ever missed stays wrong until
     * Redis is restarted or runs out of memory - and Redis's own eviction policy only intervenes when
     * memory is full, which is a capacity control rather than a correctness one.
     */
    // Not named cacheConfiguration(): a @Bean method takes its name from the method, and this class
    // is already registered as the bean "cacheConfiguration", so that collides and the context
    // refuses to start.
    @Bean
    public RedisCacheConfiguration redisCacheDefaults() {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(PRODUCT_TTL)
                // Keys stay plain strings so `redis-cli KEYS 'products*'` is readable.
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                // A null return is not cached. Caching "this product does not exist" would be a
                // reasonable defence against a lookup storm, but it also means a newly created
                // product stays invisible until the entry expires.
                .disableCachingNullValues();
    }

    /**
     * Per-cache time to live, and per-cache value type.
     *
     * <p>Both caches are configured explicitly rather than inheriting a value serializer, because
     * each has to be told what it stores.
     */
    @Bean
    public RedisCacheManagerBuilderCustomizer perCacheConfiguration(RedisCacheConfiguration base) {
        RedisCacheConfiguration products = base
                .entryTtl(PRODUCT_TTL)
                .serializeValuesWith(serializeAs(TypeFactory.createDefaultInstance()
                        .constructType(ProductResponse.class)));

        RedisCacheConfiguration productList = base
                .entryTtl(PRODUCT_LIST_TTL)
                .serializeValuesWith(serializeAs(TypeFactory.createDefaultInstance()
                        .constructCollectionType(List.class, ProductResponse.class)));

        return builder -> builder
                .withCacheConfiguration(PRODUCTS, products)
                .withCacheConfiguration(PRODUCT_LIST, productList);
    }

    /**
     * A serializer that knows exactly what it will read back.
     *
     * <p>Spelling out the generic type matters most for the list: erasure means a bare {@code List}
     * deserialises into a list of {@code LinkedHashMap}, and that surfaces as a
     * {@code ClassCastException} in whatever code read the cache rather than anywhere near here.
     */
    @SuppressWarnings("unchecked")
    private static RedisSerializationContext.SerializationPair<Object> serializeAs(JavaType type) {
        return RedisSerializationContext.SerializationPair.fromSerializer(
                (org.springframework.data.redis.serializer.RedisSerializer<Object>)
                        new JacksonJsonRedisSerializer<>(type));
    }
}
