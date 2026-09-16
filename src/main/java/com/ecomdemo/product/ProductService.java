package com.ecomdemo.product;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import com.ecomdemo.common.NotFoundException;
import com.ecomdemo.product.dto.ProductRequest;
import com.ecomdemo.product.dto.ProductResponse;

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

    public List<ProductResponse> findAll() {
        return productRepository.findAll().stream()
                .map(ProductResponse::from)
                .toList();
    }

    public ProductResponse findById(Long id) {
        return ProductResponse.from(requireProduct(id));
    }

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

    @Transactional
    public void delete(Long id) {
        productRepository.delete(requireProduct(id));
    }

    /**
     * Shared lookup used by this service and, via {@link #requireEntity(Long)}, by the cart and
     * order services. Throwing here means no caller ever handles an empty Optional.
     */
    public Product requireEntity(Long id) {
        return requireProduct(id);
    }

    private Product requireProduct(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Product", id));
    }

    private static BigDecimal normalise(BigDecimal price) {
        return price.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
