package com.ecomdemo.product;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import com.ecomdemo.shared.NotFoundException;
import com.ecomdemo.product.dto.ProductRequest;
import com.ecomdemo.product.dto.ProductResponse;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for the catalogue. The controller does no work beyond HTTP; the repository does no
 * work beyond persistence. Everything in between - the transaction boundary, the entity-to-DTO
 * mapping, the "does it exist?" check - belongs here.
 *
 * <p>Every method here is now cacheable without reservation, which was not true before. Phase 13 had
 * to carve out {@code requireEntity} as the one lookup that must never be served from Redis, because
 * it carried stock and fed a decision. With stock in another service, everything this class returns
 * is display data - so "cache what is read to be displayed, never what is read to make a decision"
 * stopped being a rule about which method to annotate and became a property of where the data lives.
 */
@Service
@Transactional(readOnly = true)
public class ProductService {

    /** Money is normalised to 2 decimal places on the way in, so 9.999 can never be stored. */
    private static final int MONEY_SCALE = 2;

    private final ProductRepository productRepository;

    /**
     * Constructor injection. A single constructor needs no @Autowired, the dependency can be final,
     * and the class stays trivially instantiable in a plain unit test.
     */
    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /**
     * The whole catalogue, cached under one key.
     *
     * <p>One key for the entire list is a deliberate simplification with a real cost: any product
     * created, updated or deleted invalidates all of it, so a busy catalogue would thrash. It suits
     * this one - read constantly, written rarely - and the alternative (caching pages, or assembling
     * the list from individually cached products) is a great deal of machinery for a shop with ten
     * products.
     */
    @Cacheable(cacheNames = CacheConfiguration.PRODUCT_LIST, key = CacheConfiguration.WHOLE_LIST_KEY)
    public List<ProductResponse> findAll() {
        return productRepository.findAll().stream()
                .map(ProductResponse::from)
                .toList();
    }

    /**
     * One product by id.
     *
     * <p>This is the read worth caching: it is the most frequent query in the application and the
     * least volatile answer. The key is the id, so entries are evicted precisely rather than
     * wholesale.
     */
    @Cacheable(cacheNames = CacheConfiguration.PRODUCTS, key = "#id")
    public ProductResponse findById(Long id) {
        return ProductResponse.from(requireProduct(id));
    }

    /**
     * A new product changes what the catalogue contains, so the list entry is dropped. The product's
     * own entry needs nothing: nobody has asked for an id that did not exist a moment ago.
     */
    @CacheEvict(cacheNames = CacheConfiguration.PRODUCT_LIST, key = CacheConfiguration.WHOLE_LIST_KEY)
    @Transactional
    public ProductResponse create(ProductRequest request) {
        Product product = new Product(
                request.name(),
                request.description(),
                normalise(request.price()));
        product.setCategory(request.category());
        return ProductResponse.from(productRepository.save(product));
    }

    /**
     * Update writes through to the cache rather than evicting it.
     *
     * <p>{@code @CachePut} always runs the method and then stores what it returned, so the next
     * reader gets the new value from Redis instead of paying for a miss. That is only safe because
     * the returned value is exactly what a subsequent {@code findById} would produce - if the two
     * ever diverged, {@code @CacheEvict} would be the honest choice.
     */
    @Caching(
            put = @CachePut(cacheNames = CacheConfiguration.PRODUCTS, key = "#id"),
            evict = @CacheEvict(cacheNames = CacheConfiguration.PRODUCT_LIST, key = CacheConfiguration.WHOLE_LIST_KEY))
    @Transactional
    public ProductResponse update(Long id, ProductRequest request) {
        Product product = requireProduct(id);
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(normalise(request.price()));
        product.setCategory(request.category());
        // No save() call needed: the entity is managed inside this transaction, so Hibernate
        // flushes the changes automatically at commit. This is "dirty checking".
        return ProductResponse.from(product);
    }

    /** Both caches, because the product is gone from each. */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfiguration.PRODUCTS, key = "#id"),
            @CacheEvict(cacheNames = CacheConfiguration.PRODUCT_LIST, key = CacheConfiguration.WHOLE_LIST_KEY)})
    @Transactional
    public void delete(Long id) {
        productRepository.delete(requireProduct(id));
    }

    /*
     * Two methods that were here in Phase 13 and are not any more, both deleted by the split rather
     * than by a refactor:
     *
     *   requireEntity(Long) returned a managed Product carrying stockQuantity, and was what the cart
     *   and checkout called to make their stock decisions. There is no stock on this entity now, and
     *   no caller in this process - order-service reads prices over HTTP and asks inventory-service
     *   about availability. A method that hands out a live entity is exactly what cannot cross a
     *   service boundary: the thing on the other side would get a detached copy with none of the
     *   transactional guarantees that made it useful.
     *
     *   evictFromCache(Long) existed so that checkout could drop a product from the cache after
     *   reducing its stock. Nothing outside this service changes a product any more, because stock
     *   is not a product field, so there is nothing left to evict for. The awkward coupling Phase 13
     *   accepted - the order feature having to know the catalogue was cached - went away as a side
     *   effect of putting the two fields in the services that own them.
     */

    private Product requireProduct(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Product", id));
    }

    private static BigDecimal normalise(BigDecimal price) {
        return price.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
