package com.ecomdemo.product;

import java.util.Collection;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

/**
 * Makes cache hits and misses visible.
 *
 * <p>Spring's cache abstraction is deliberately quiet: the whole point of {@code @Cacheable} is that
 * calling code cannot tell whether it was served from the cache. That is exactly what makes a cache
 * hard to learn from and hard to debug - "is this even working?" has no answer from the outside, and
 * a cache that silently never hits looks identical to one that always does, only slower.
 *
 * <p>The decoration is applied by a {@link BeanPostProcessor} rather than by defining a
 * {@code CacheManager} bean directly. Declaring one would make Spring Boot's auto-configuration back
 * off, and with it the Redis serializer and per-cache TTLs in {@link CacheConfiguration}. Wrapping
 * afterwards keeps all of that and adds logging on top.
 *
 * <p>Logged at DEBUG: useful while learning, silent by default in production, where per-request cache
 * logging is the kind of thing that fills a disk.
 */
@Configuration
public class CacheLogging {

    private static final Logger log = LoggerFactory.getLogger("com.ecomdemo.cache");

    /**
     * Wraps whatever {@code CacheManager} the auto-configuration produced.
     */
    @org.springframework.context.annotation.Bean
    public static BeanPostProcessor cacheManagerLoggingDecorator() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof CacheManager manager && !(bean instanceof LoggingCacheManager)) {
                    log.debug("Cache logging enabled, wrapping {}", bean.getClass().getSimpleName());
                    return new LoggingCacheManager(manager);
                }
                return bean;
            }
        };
    }

    /** Delegates everything, wrapping each cache on the way out. */
    private record LoggingCacheManager(CacheManager delegate) implements CacheManager {

        @Override
        public Cache getCache(String name) {
            Cache cache = delegate.getCache(name);
            return cache == null ? null : new LoggingCache(cache);
        }

        @Override
        public Collection<String> getCacheNames() {
            return delegate.getCacheNames();
        }
    }

    /**
     * Logs every lookup as HIT or MISS, and every write.
     *
     * <p>Both {@code get} overloads are instrumented because Spring uses different ones depending on
     * how the annotation is configured: the two-argument form is what {@code @Cacheable(sync = true)}
     * takes, and instrumenting only the other would report nothing for those methods.
     */
    private record LoggingCache(Cache delegate) implements Cache {

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public Object getNativeCache() {
            return delegate.getNativeCache();
        }

        @Override
        @Nullable
        public ValueWrapper get(Object key) {
            ValueWrapper value = delegate.get(key);
            log.debug("{} {}::{}", value != null ? "HIT " : "MISS", getName(), key);
            return value;
        }

        @Override
        @Nullable
        public <T> T get(Object key, @Nullable Class<T> type) {
            T value = delegate.get(key, type);
            log.debug("{} {}::{}", value != null ? "HIT " : "MISS", getName(), key);
            return value;
        }

        @Override
        @Nullable
        public <T> T get(Object key, Callable<T> valueLoader) {
            // Cannot tell a hit from a miss by the return value here - the loader produces one
            // either way - so the miss is reported from inside the loader.
            return delegate.get(key, () -> {
                log.debug("MISS {}::{} (loading)", getName(), key);
                return valueLoader.call();
            });
        }

        @Override
        public void put(Object key, @Nullable Object value) {
            log.debug("PUT  {}::{}", getName(), key);
            delegate.put(key, value);
        }

        @Override
        public void evict(Object key) {
            log.debug("EVICT {}::{}", getName(), key);
            delegate.evict(key);
        }

        @Override
        public void clear() {
            log.debug("CLEAR {} (all entries)", getName());
            delegate.clear();
        }
    }
}
