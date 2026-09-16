package com.ecomdemo.order.client;

import java.util.List;

import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * order-service's view of catalog-service: an interface, not a URL.
 *
 * <h2>Why an HTTP Interface rather than RestClient calls</h2>
 *
 * {@code RestClient} would work and would be four lines longer per call. What the declarative form
 * buys is that the calling code - {@link com.ecomdemo.order.OrderPlacement} - reads as
 * {@code catalogClient.findById(id)} and contains no host, no path and no status handling. The
 * dependency between two services is visible in one file, and the host it resolves to is a property
 * (see {@code ecomdemo.clients.catalog.base-url}) rather than something compiled in.
 *
 * <p>Spring builds the implementation at startup from this interface over a {@code RestClient} - the
 * same machinery, with the plumbing generated.
 *
 * <h2>What can go wrong here, and what is not done about it</h2>
 *
 * Every method on this interface is a network call, and every network call has three outcomes rather
 * than two: it works, it fails, or - worst - it hangs. Timeouts are configured on the underlying
 * {@code RestClient} in {@link ServiceClientsConfiguration}, which is the minimum.
 *
 * <p>What is deliberately absent is a circuit breaker, a retry policy and a fallback. If
 * catalog-service is down, checkout fails with a 503 and says so. That is honest and it is not good
 * enough for production: a dependency that is merely slow will hold this service's threads until it
 * runs out of them, which is how one service's bad afternoon becomes an outage in three. Resilience4j
 * is Phase 22, and implementing it here would be implementing a later phase.
 */
@HttpExchange("/api/products")
public interface CatalogClient {

    /**
     * One product's name and price.
     *
     * <p>Used at checkout to price the lines being bought. Note that this is the <em>current</em>
     * price, read at the moment of purchase, and that the result is immediately snapshotted into
     * {@code order_items} - so a price change a second later cannot rewrite what somebody paid.
     */
    @GetExchange("/{id}")
    CatalogProduct findById(Long id);

    /**
     * Every product, for pricing a whole cart without one call per line.
     *
     * <p>catalog-service has no "give me these ids" endpoint, so this fetches the lot and the caller
     * picks out what it needs. That is fine for a shop with ten products and would not be for one
     * with ten thousand - at which point the right fix is a batch endpoint there rather than a loop
     * here. Worth being explicit about, because turning an in-process loop into a per-line HTTP call
     * is the classic way a split makes a page twenty times slower without anyone changing an
     * algorithm.
     */
    @GetExchange
    List<CatalogProduct> findAll();
}
