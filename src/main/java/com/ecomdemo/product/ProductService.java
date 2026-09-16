package com.ecomdemo.product;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import com.ecomdemo.common.CacheConfiguration;
import com.ecomdemo.common.NotFoundException;
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
    @Cacheable(cacheNames = CacheConfiguration.PRODUCT_LIST)
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
    @CacheEvict(cacheNames = CacheConfiguration.PRODUCT_LIST, allEntries = true)
    @Transactional
    public ProductResponse create(ProductRequest request) {
        Product product = new Product(
                request.name(),
                request.description(),
                normalise(request.price()),
                request.stockQuantity());
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
            evict = @CacheEvict(cacheNames = CacheConfiguration.PRODUCT_LIST, allEntries = true))
    @Transactional
    public ProductResponse update(Long id, ProductRequest request) {
        Product product = requireProduct(id);
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(normalise(request.price()));
        product.setStockQuantity(request.stockQuantity());
        product.setCategory(request.category());
        // No save() call needed: the entity is managed inside this transaction, so Hibernate
        // flushes the changes automatically at commit. This is "dirty checking".
        return ProductResponse.from(product);
    }

    /** Both caches, because the product is gone from each. */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfiguration.PRODUCTS, key = "#id"),
            @CacheEvict(cacheNames = CacheConfiguration.PRODUCT_LIST, allEntries = true)})
    @Transactional
    public void delete(Long id) {
        productRepository.delete(requireProduct(id));
    }

    /**
     * Shared lookup used by this service and, via {@link #requireEntity(Long)}, by the cart and
     * order services. Throwing here means no caller ever handles an empty Optional.
     */
    /**
     * The uncached lookup, and the most important method in this class to leave alone.
     *
     * <p>This is what the cart and checkout call, and it returns a managed entity carrying
     * {@code stockQuantity}. Caching it would hand two simultaneous shoppers the same remembered
     * stock figure and let both pass the "is there enough?" check - quietly undoing the optimistic
     * locking Phase 6 added, and overselling exactly the last unit that phase exists to protect.
     *
     * <p>More generally: cache what is read to be displayed, never what is read to make a decision.
     */
    public Product requireEntity(Long id) {
        return requireProduct(id);
    }

    /**
     * Drops a product from both caches after something outside this feature changed it - checkout
     * reducing stock, in practice.
     *
     * <p>The body is empty on purpose: the work is done by the annotations, which Spring applies
     * through its proxy when another bean calls this. That is also why it cannot be called from
     * inside this class - a self-invocation never reaches the proxy, and the eviction would silently
     * not happen.
     *
     * <p>It does couple the order feature to the knowledge that products are cached. The alternative
     * was letting the catalogue show stock that checkout had already changed, and a visibly wrong
     * number is worse than a named dependency.
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfiguration.PRODUCTS, key = "#productId"),
            @CacheEvict(cacheNames = CacheConfiguration.PRODUCT_LIST, allEntries = true)})
    public void evictFromCache(Long productId) {
        // Intentionally empty - see the Javadoc.
    }

    private Product requireProduct(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Product", id));
    }

    private static BigDecimal normalise(BigDecimal price) {
        return price.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
