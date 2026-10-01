package com.stocklock.product.dto;

import com.stocklock.product.Product;
import java.math.BigDecimal;
import java.time.Instant;

public record ProductResponse(
        long id,
        String sku,
        String name,
        String description,
        BigDecimal price,
        Instant createdAt,
        Instant updatedAt) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(product.getId(), product.getSku(), product.getName(), product.getDescription(),
                product.getPrice(), product.getCreatedAt(), product.getUpdatedAt());
    }
}
