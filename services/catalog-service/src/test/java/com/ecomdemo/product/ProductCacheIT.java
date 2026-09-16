package com.ecomdemo.product;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;


import com.ecomdemo.product.dto.ProductRequest;
import com.ecomdemo.product.dto.ProductResponse;
import com.ecomdemo.support.AbstractCatalogServiceIT;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Proves the cache is actually a cache: that a second read does not reach PostgreSQL, and that every
 * write path makes the stale entry go away.
 *
 * <p>The technique throughout is to <strong>change the database behind the cache's back</strong> -
 * writing through {@link ProductRepository} directly, which no annotation watches - and then read
 * through {@link ProductService}. If the old value comes back, the read was served from Redis and
 * never touched the row. That is a stronger statement than counting queries, and it does not depend
 * on reading log output.
 *
 * <p>Caching is on here because integration tests run under the {@code dev} profile, against the
 * Redis container in {@link AbstractCatalogServiceIT}. The unit and slice suite runs with
 * {@code spring.cache.type=none}, so it neither needs Redis nor tests it.
 *
 * <h2>Why so much of this waits</h2>
 *
 * Nearly every read of the cache here is wrapped in {@code await()}, and the reason is a property of
 * the cache rather than a flaky test. In Spring Data Redis 4,
 * {@code RedisCacheWriter.store} returns a {@code CompletableFuture} - a cache write is dispatched,
 * not completed, by the time {@code @Cacheable} or {@code @CachePut} returns. Two consequences, both
 * observed here: a {@code put} still in flight can reach Redis <em>after</em> a {@code del} issued
 * later, so an evicted entry reappears; and a read immediately after a write-through can still be
 * served the value that write is replacing.
 *
 * <p>It bites in setup as much as in assertions: reading an entry straight after the {@code findById}
 * that should have cached it can find nothing there yet, which reads as "the cache is not working"
 * when the cache was one round-trip behind.
 *
 * <p>The window is sub-millisecond and it took until Phase 17 to see it: the Kafka listener
 * containers added polling threads to the same JVM, shifted the scheduling, and turned always-green
 * assertions into ones that failed roughly two runs in five. Nothing in the caching code changed.
 *
 * <p>Waiting is the honest assertion, not a weakened one. What these tests must prove is that a read
 * is served from Redis and that a write path <em>evicts</em> rather than leaving the entry to expire.
 * The TTLs are minutes, so a two-second window separates "evicted" from "expired" just as decisively
 * as an immediate read would, and it no longer depends on winning a race with the cache's own write.
 */
class ProductCacheIT extends AbstractCatalogServiceIT {

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CacheManager cacheManager;

    /** Talks to Redis directly, to assert on what is actually stored rather than on behaviour alone. */
    @Autowired
    private StringRedisTemplate redis;

    private ProductResponse createProduct(String name, String price) {
        return productService.create(new ProductRequest(
                name + " " + System.nanoTime(), "cache test", new BigDecimal(price), null));
    }

    /**
     * Waits for a product's cache entry to actually exist.
     *
     * <p>Needed because a cache write is dispatched rather than completed when the cached method
     * returns - see the class comment. Two seconds is three orders of magnitude more than the write
     * takes and three orders of magnitude less than the TTL, so it can only ever hide the race it is
     * there to absorb.
     */
    private void awaitCached(Long productId) {
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(cacheManager.getCache(CacheConfiguration.PRODUCTS).get(productId))
                        .isNotNull());
    }

    /** Changes the row without going through any cached method. */
    private void renameBehindTheCachesBack(Long id, String newName) {
        Product product = productRepository.findById(id).orElseThrow();
        product.setName(newName);
        productRepository.saveAndFlush(product);
    }

    @Nested
    class ASecondReadIsServedFromRedis {

        @Test
        void findById_calledTwice_returnsTheCachedValueTheSecondTime() {
            // GIVEN a product read once, and that read actually in the cache.
            //
            // Waiting for the entry to appear is what makes the rest of this a test of caching
            // rather than a race: if the write were still in flight, the second read below would
            // miss, reach the database, and return the new name - reported as "the cache is not
            // working" when the cache was merely one round-trip behind.
            ProductResponse created = createProduct("Cached Widget", "10.00");
            String originalName = productService.findById(created.id()).name();
            awaitCached(created.id());

            // WHEN the row is changed without going through the service
            renameBehindTheCachesBack(created.id(), "Changed In The Database");

            // THEN the second read still returns the old value, so it cannot have read the row
            assertThat(productService.findById(created.id()).name())
                    .as("a second read served from Redis never sees the database change")
                    .isEqualTo(originalName)
                    .isNotEqualTo("Changed In The Database");
        }

        @Test
        void findById_always_writesAnEntryIntoRedisUnderAReadableKey() {
            // GIVEN
            ProductResponse created = createProduct("Key Visible", "12.00");

            // WHEN
            productService.findById(created.id());

            // THEN the key is there, and it is legible - which is the practical argument for JSON
            // over Java serialization: redis-cli tells you what is cached.
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(redis.keys("products::*"))
                            .anyMatch(key -> key.endsWith("::" + created.id())));
        }

        @Test
        void findAll_calledTwice_isServedFromTheListCache() {
            // GIVEN the catalogue read once, and that read actually in the cache
            ProductResponse created = createProduct("In The List", "20.00");
            int sizeAfterFirstRead = productService.findAll().size();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(cacheManager.getCache(CacheConfiguration.PRODUCT_LIST).get("all"))
                            .isNotNull());

            // WHEN another product appears in the database without the service knowing
            productRepository.saveAndFlush(new Product(
                    "Snuck In " + System.nanoTime(), "not via the service", new BigDecimal("1.00")));

            // THEN the cached list is unchanged
            assertThat(productService.findAll())
                    .as("the list came from Redis, so the new row is invisible")
                    .hasSize(sizeAfterFirstRead);
            assertThat(created).isNotNull();
        }
    }

    @Nested
    class WritesInvalidateTheCache {

        @Test
        void update_always_replacesTheCachedValue() {
            // GIVEN a cached product
            ProductResponse created = createProduct("Before Update", "10.00");
            productService.findById(created.id());

            // WHEN it is updated through the service
            productService.update(created.id(), new ProductRequest(
                    "After Update", "updated", new BigDecimal("99.00"), "Changed"));

            // THEN the next read sees the new value. @CachePut wrote it through rather than merely
            // evicting, so this read is still a cache hit - just of the right value.
            //
            // Waited for, for the same reason as the two below: the write-through is dispatched
            // rather than completed when update() returns, so an immediate read can still be served
            // the value @CachePut is in the middle of replacing.
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                ProductResponse afterwards = productService.findById(created.id());
                assertThat(afterwards.name()).isEqualTo("After Update");
                assertThat(afterwards.price()).isEqualByComparingTo("99.00");
            });
        }

        @Test
        void delete_always_removesTheEntry() {
            // GIVEN a cached product.
            //
            // The precondition waits for the same reason the assertions do - @Cacheable's write is
            // dispatched rather than completed when findById returns, so reading the entry straight
            // afterwards can find nothing there yet. A setup step that fails intermittently is worse
            // than an assertion that does: it reads as the behaviour under test being broken.
            ProductResponse created = createProduct("To Be Deleted", "10.00");
            productService.findById(created.id());
            awaitCached(created.id());

            // WHEN
            productService.delete(created.id());

            // THEN nothing is left behind to serve a deleted product from
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(cacheManager.getCache("products").get(created.id())).isNull());
        }

        @Test
        void create_always_invalidatesTheList() {
            // GIVEN the catalogue cached, starting from a known state.
            //
            // The explicit eviction matters: every integration test in the run shares one Redis, so
            // without it this test inherits whatever list a previous one left behind and is really
            // asserting about that. It failed once on CI and never locally, which is the signature
            // of exactly that kind of shared-state dependency.
            cacheManager.getCache(CacheConfiguration.PRODUCT_LIST).evict("all");
            List<Long> before = productService.findAll().stream().map(ProductResponse::id).toList();

            // WHEN a product is created through the service
            ProductResponse created = createProduct("Appears Immediately", "15.00");

            // THEN it becomes visible - the list entry was dropped rather than left to expire.
            // The TTL is minutes, so anything inside two seconds can only be the eviction.
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(productService.findAll())
                            .as("cached list before create was %s; %d must appear after the eviction",
                                    before, created.id())
                            .extracting(ProductResponse::id)
                            .contains(created.id()));
        }
    }

    /*
     * A third group lived here until Phase 20: StockIsNeverServedStale.
     *
     * It asserted that ProductService.requireEntity read the database rather than the cache, and
     * that placing an order evicted the product whose stock it had changed. Both are meaningless
     * now - this service has no stock column, no requireEntity, and no knowledge that orders exist.
     *
     * The rule those tests defended has not gone away; it has been satisfied structurally instead.
     * "Never cache what is read to make a decision" used to require care about which method carried
     * which annotation, because display data and decision data were fields on the same row. They are
     * in different databases now, and the only service with a cache is the one holding nothing a
     * checkout depends on. inventory-service owns the decision data and has no Redis at all.
     *
     * The equivalent coverage - that two concurrent orders cannot oversell the last unit - is
     * StockReservationConcurrencyIT in inventory-service.
     */
}
