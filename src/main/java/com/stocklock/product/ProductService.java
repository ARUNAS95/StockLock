package com.stocklock.product;

import com.stocklock.common.web.PageResponse;
import com.stocklock.inventory.InventoryService;
import com.stocklock.product.dto.CreateProductRequest;
import com.stocklock.product.dto.ProductResponse;
import java.time.Clock;
import java.time.Instant;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);
    private static final String SKU_CONSTRAINT = "uk_products_sku";

    private final ProductRepository productRepository;
    private final InventoryService inventoryService;
    private final Clock clock;

    public ProductService(ProductRepository productRepository, InventoryService inventoryService, Clock clock) {
        this.productRepository = productRepository;
        this.inventoryService = inventoryService;
        this.clock = clock;
    }

    /** Creates the product and its inventory row atomically: a product never exists without stock tracking. */
    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        Instant now = Instant.now(clock);
        Product product;
        try {
            // SKU uniqueness is enforced by the database; a check-then-insert would race.
            product = productRepository.saveAndFlush(
                    Product.create(request.sku(), request.name(), request.description(), request.price(), now));
        } catch (DataIntegrityViolationException e) {
            if (e.getCause() instanceof ConstraintViolationException cve
                    && SKU_CONSTRAINT.equals(cve.getConstraintName())) {
                throw new DuplicateSkuException(request.sku());
            }
            throw e;
        }
        inventoryService.createFor(product.getId(), request.initialQuantity(), now);
        log.info("Product created: productId={} sku={} initialQuantity={}",
                product.getId(), product.getSku(), request.initialQuantity());
        return ProductResponse.from(product);
    }

    @Transactional(readOnly = true)
    public ProductResponse get(long productId) {
        return productRepository.findById(productId)
                .map(ProductResponse::from)
                .orElseThrow(() -> new ProductNotFoundException(productId));
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> list(Pageable pageable) {
        return PageResponse.from(productRepository.findAll(pageable), ProductResponse::from);
    }
}
