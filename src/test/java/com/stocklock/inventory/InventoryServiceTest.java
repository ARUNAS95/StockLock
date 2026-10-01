package com.stocklock.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stocklock.common.metrics.StockLockMetrics;
import com.stocklock.product.Product;
import com.stocklock.product.ProductNotFoundException;
import com.stocklock.product.ProductRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class InventoryServiceTest {

    private final InventoryRepository inventoryRepository = mock(InventoryRepository.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final InventoryService service = new InventoryService(inventoryRepository, productRepository,
            new StockLockMetrics(registry), Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void reserveSucceedsWhenConditionalUpdateMatchesOneRow() {
        when(inventoryRepository.reserve(eq(7L), eq(2), any())).thenReturn(1);

        service.reserve(7L, 2);

        assertThat(registry.get("inventory.out_of_stock").counter().count()).isZero();
    }

    @Test
    void zeroRowsForExistingProductMeansOutOfStock() {
        when(inventoryRepository.reserve(eq(7L), eq(2), any())).thenReturn(0);
        when(productRepository.findById(7L)).thenReturn(Optional.of(
                Product.create("SKU-123", "n", null, BigDecimal.ONE, Instant.now())));

        assertThatThrownBy(() -> service.reserve(7L, 2))
                .isInstanceOf(OutOfStockException.class)
                .hasMessage("Insufficient inventory for product SKU-123 (requested 2)");
        assertThat(registry.get("inventory.out_of_stock").counter().count()).isEqualTo(1);
    }

    @Test
    void zeroRowsForUnknownProductMeansNotFound() {
        when(inventoryRepository.reserve(eq(7L), eq(2), any())).thenReturn(0);
        when(productRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reserve(7L, 2)).isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void releasesInAscendingProductOrderToAvoidDeadlocks() {
        when(inventoryRepository.release(anyLong(), anyInt(), any())).thenReturn(1);

        service.release(Map.of(30L, 1, 10L, 2, 20L, 3));

        InOrder order = inOrder(inventoryRepository);
        order.verify(inventoryRepository).release(eq(10L), eq(2), any());
        order.verify(inventoryRepository).release(eq(20L), eq(3), any());
        order.verify(inventoryRepository).release(eq(30L), eq(1), any());
    }

    @Test
    void releaseThatMatchesNoRowIsAnInvariantViolation() {
        when(inventoryRepository.release(anyLong(), anyInt(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.release(Map.of(1L, 5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invariant");
    }
}
