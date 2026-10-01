package com.stocklock.inventory;

import com.stocklock.inventory.dto.InventoryResponse;
import com.stocklock.inventory.dto.RestockRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products/{productId}/inventory")
@Tag(name = "Inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping
    @Operation(summary = "Get available and reserved stock for a product")
    public InventoryResponse get(@PathVariable long productId) {
        return inventoryService.get(productId);
    }

    @PostMapping("/restock")
    @Operation(summary = "Atomically add stock to a product")
    public InventoryResponse restock(@PathVariable long productId, @Valid @RequestBody RestockRequest request) {
        return inventoryService.restock(productId, request.quantity());
    }
}
