package com.stocklock.events;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record OrderEventPayload(
        UUID orderId,
        String userId,
        String status,
        BigDecimal totalAmount,
        List<Item> items) {

    public record Item(UUID reservationId, long productId, int quantity, BigDecimal price) {
    }
}
