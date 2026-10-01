package com.stocklock.inventory.dto;

import com.stocklock.inventory.Inventory;
import java.time.Instant;

public record InventoryResponse(
        long productId,
        int availableQuantity,
        int reservedQuantity,
        long version,
        Instant updatedAt) {

    public static InventoryResponse from(Inventory inventory) {
        return new InventoryResponse(inventory.getProductId(), inventory.getAvailableQuantity(),
                inventory.getReservedQuantity(), inventory.getVersion(), inventory.getUpdatedAt());
    }
}
