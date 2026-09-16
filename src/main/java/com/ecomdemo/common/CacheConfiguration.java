package com.ecomdemo.common;

import java.time.Duration;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

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
 * <p>JSON fixes the first two outright. The third needs care even here, which is what the type
 * validator below is for: to rebuild a {@code ProductResponse} the serializer has to record its type
 * in the JSON, and a serializer that will instantiate <em>any</em> named class has reintroduced the
 * same gadget problem in a friendlier format. The validator restricts that to this application's own
 * types and the collections that hold them.
 */
@Configuration
@EnableCaching
public class CacheConfiguration {

    /** One product, keyed by id. */
    public static final String PRODUCTS = "products";

    /** The whole catalogue, under a single key. */
    public static final String PRODUCT_LIST = "product-list";

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
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(jsonSerializer()))
                // A null return is not cached. Caching "this product does not exist" would be a
                // reasonable choice against a lookup storm, but it also means a newly created product
                // stays invisible until the entry expires.
                .disableCachingNullValues();
    }

    private static GenericJacksonJsonRedisSerializer jsonSerializer() {
        /*
         * Only this application's own types, plus the collections that carry them, may be
         * reconstructed from a type name in the JSON. Anything else - the classic deserialization
         * gadget in some library on the classpath - is refused.
         */
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.ecomdemo.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.math.")
                .allowIfSubType("java.time.")
                .build();

        return GenericJacksonJsonRedisSerializer.builder()
                .enableDefaultTyping(typeValidator)
                // Spring Cache stores a marker object for a cached null; without this the serializer
                // does not know how to write it.
                .enableSpringCacheNullValueSupport()
                .build();
    }

    /**
     * Per-cache time to live. Everything else inherits the default above.
     */
    @Bean
    public RedisCacheManagerBuilderCustomizer perCacheTimeToLive(RedisCacheConfiguration base) {
        return builder -> builder
                .withCacheConfiguration(PRODUCTS, base.entryTtl(PRODUCT_TTL))
                .withCacheConfiguration(PRODUCT_LIST, base.entryTtl(PRODUCT_LIST_TTL));
    }
}
