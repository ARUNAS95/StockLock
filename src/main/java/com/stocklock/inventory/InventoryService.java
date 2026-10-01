package com.stocklock.inventory;

import com.stocklock.common.metrics.StockLockMetrics;
import com.stocklock.inventory.dto.InventoryResponse;
import com.stocklock.product.ProductNotFoundException;
import com.stocklock.product.ProductRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock mutations. The methods that move stock between "available" and "reserved" are
 * {@link Propagation#MANDATORY}: moving stock is only meaningful together with the reservation or
 * order change that justifies it, so they must join the caller's transaction and can never commit
 * on their own.
 *
 * <p>Multi-product operations lock inventory rows in ascending product id order. Every transaction
 * that touches several inventory rows follows that order, which rules out lock-ordering deadlocks
 * between, for example, two expiration sweepers on different instances.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final InventoryRepository inventoryRepository;
    private final ProductRepository productRepository;
    private final StockLockMetrics metrics;
    private final Clock clock;

    public InventoryService(InventoryRepository inventoryRepository, ProductRepository productRepository,
                            StockLockMetrics metrics, Clock clock) {
        this.inventoryRepository = inventoryRepository;
        this.productRepository = productRepository;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void createFor(long productId, int initialQuantity, Instant now) {
        inventoryRepository.save(Inventory.initial(productId, initialQuantity, now));
    }

    /**
     * Moves {@code quantity} from available to reserved, or throws if there is not enough stock.
     * The throw happens inside the caller's transaction, so nothing else it did is committed either.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(long productId, int quantity) {
        int updated = inventoryRepository.reserve(productId, quantity, Instant.now(clock));
        if (updated == 1) {
            return;
        }
        // Zero rows: either the guard rejected the update or there is no such product.
        String sku = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId))
                .getSku();
        metrics.outOfStock();
        throw new OutOfStockException(sku, quantity);
    }

    /** Returns reserved stock to available stock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(Map<Long, Integer> quantityByProduct) {
        Instant now = Instant.now(clock);
        new TreeMap<>(quantityByProduct).forEach((productId, quantity) ->
                requireApplied(inventoryRepository.release(productId, quantity, now), "release", productId, quantity));
    }

    /** Removes reserved stock permanently because it was sold. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consumeReserved(Map<Long, Integer> quantityByProduct) {
        Instant now = Instant.now(clock);
        new TreeMap<>(quantityByProduct).forEach((productId, quantity) ->
                requireApplied(inventoryRepository.consumeReserved(productId, quantity, now), "consume", productId,
                        quantity));
    }

    @Transactional
    public InventoryResponse restock(long productId, int quantity) {
        if (inventoryRepository.restock(productId, quantity, Instant.now(clock)) == 0) {
            throw new ProductNotFoundException(productId);
        }
        log.info("Inventory restocked: productId={} quantity={}", productId, quantity);
        return get(productId);
    }

    @Transactional(readOnly = true)
    public InventoryResponse get(long productId) {
        return inventoryRepository.findByProductId(productId)
                .map(InventoryResponse::from)
                .orElseThrow(() -> new ProductNotFoundException(productId));
    }

    /**
     * A release/consume can only match zero rows if reserved_quantity is smaller than what an ACTIVE
     * reservation claims to hold, i.e. the reserved counter and the reservations table disagree.
     * That is a bug, not a business outcome, so fail loudly and roll back rather than "fix" counters.
     */
    private static void requireApplied(int updated, String operation, long productId, int quantity) {
        if (updated != 1) {
            throw new IllegalStateException("Inventory invariant violated: cannot %s %d units of product %d"
                    .formatted(operation, quantity, productId));
        }
    }
}
