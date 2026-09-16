package com.ecomdemo.order.client;

import java.util.List;

import com.ecomdemo.shared.ServiceUnavailableException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

/**
 * catalog-service, with a circuit breaker and a retry around it.
 *
 * <h2>Why this is a wrapper rather than annotations on the interface</h2>
 *
 * {@link CatalogClient} is an {@code @HttpExchange} interface whose implementation Spring generates
 * at runtime. Resilience4j's annotations are applied by an aspect to a Spring bean's methods, and
 * there is no source method on a generated proxy to annotate - so the annotations would be attached
 * to an interface nobody proxies a second time, and would silently do nothing.
 *
 * <p>This class implements the same interface and is {@link Primary}, which means
 * {@code CartService} and {@code OrderPlacement} inject {@code CatalogClient} exactly as they did
 * before and get the protected version without knowing it. Decoration that requires every caller to
 * change is decoration that gets skipped somewhere.
 *
 * <h2>Why retry sits OUTSIDE the circuit breaker</h2>
 *
 * Resilience4j applies its aspects in a fixed order, outermost first:
 * {@code Retry → CircuitBreaker → RateLimiter → TimeLimiter → Bulkhead}. That ordering is the one
 * that makes sense and it is worth understanding rather than accepting.
 *
 * <p>With retry outermost, three attempts that all fail are recorded by the breaker as <em>three</em>
 * failed calls... which is the part people get wrong when they reverse it. Put the breaker outside
 * instead and an open circuit would be retried - three fast rejections per request, achieving
 * nothing except making the caller wait three times to be told the same no. Retry outside means the
 * breaker sees each attempt, opens on the aggregate failure rate, and then short-circuits the whole
 * retry sequence at once: when the circuit is open, {@link #findById} fails immediately without any
 * attempt at all.
 *
 * <h2>What the fallback does, and what it deliberately does not</h2>
 *
 * It throws {@link ServiceUnavailableException}, which becomes a 503 with a {@code Retry-After}. It
 * does <strong>not</strong> invent a product or serve a remembered price.
 *
 * <p>That is the important restraint. A fallback's job is to fail in a way the caller can understand
 * quickly, not to make the failure invisible - and a price is exactly the kind of value that must
 * never be guessed, because the consequence of guessing is charging somebody the wrong amount. The
 * cart page degrades (it renders lines as unavailable at zero, because nothing is bought on the
 * strength of it) and checkout refuses outright. Different answers for the same outage, chosen by
 * what each one is for.
 */
@Component
@Primary
public class ResilientCatalogClient implements CatalogClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientCatalogClient.class);

    /** Names the instance configured under {@code resilience4j.*.instances.catalog}. */
    static final String CATALOG = "catalog";

    private final CatalogClient delegate;

    public ResilientCatalogClient(@Qualifier("rawCatalogClient") CatalogClient delegate) {
        this.delegate = delegate;
    }

    @Override
    @CircuitBreaker(name = CATALOG, fallbackMethod = "findByIdUnavailable")
    @Retry(name = CATALOG)
    public CatalogProduct findById(Long id) {
        return delegate.findById(id);
    }

    @Override
    @CircuitBreaker(name = CATALOG, fallbackMethod = "findAllUnavailable")
    @Retry(name = CATALOG)
    public List<CatalogProduct> findAll() {
        return delegate.findAll();
    }

    /*
     * The fallbacks.
     *
     * A fallback method must have the same signature as the guarded one plus a trailing Throwable,
     * and Resilience4j matches the MOST SPECIFIC exception type it can. That is why there are two
     * overloads per method rather than one taking Throwable: a 404 from catalog-service is not a
     * failure of catalog-service, and turning "this product does not exist" into "the catalogue is
     * down" would be a worse answer than the one we started with.
     *
     * Matching by signature rather than by an annotation attribute is a real trap: a typo in the
     * method name, or a parameter list that does not line up, is reported at runtime as
     * "fallbackMethod ... not found" on the first failure - which is to say, during an outage.
     */

    /** A 4xx is the caller's answer, not a fault. Re-thrown untouched so the 404 stays a 404. */
    @SuppressWarnings("unused")
    private CatalogProduct findByIdUnavailable(Long id, RestClientResponseException ex) {
        throw ex;
    }

    @SuppressWarnings("unused")
    private CatalogProduct findByIdUnavailable(Long id, Throwable cause) {
        throw unavailable("product " + id, cause);
    }

    @SuppressWarnings("unused")
    private List<CatalogProduct> findAllUnavailable(RestClientResponseException ex) {
        throw ex;
    }

    @SuppressWarnings("unused")
    private List<CatalogProduct> findAllUnavailable(Throwable cause) {
        throw unavailable("the catalogue", cause);
    }

    /**
     * Turns whatever went wrong into one sentence a shopper could read.
     *
     * <p>{@link CallNotPermittedException} is worth distinguishing in the log even though the caller
     * sees the same 503: it means the breaker is open, so this request never left the process. When
     * a dashboard shows thousands of 503s, "we are not even trying" and "every attempt is timing
     * out" call for completely different responses.
     */
    private ServiceUnavailableException unavailable(String what, Throwable cause) {
        if (cause instanceof CallNotPermittedException) {
            log.warn("Circuit open for catalog-service; {} not attempted", what);
        } else {
            log.warn("catalog-service failed for {}: {}", what, cause.toString());
        }
        return new ServiceUnavailableException(
                "The product catalogue is temporarily unavailable. Please try again shortly.", cause);
    }
}
