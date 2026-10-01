package com.stocklock.order.dto;

import com.stocklock.order.Order;
import com.stocklock.order.OrderItem;
import com.stocklock.order.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID orderId,
        String userId,
        OrderStatus status,
        BigDecimal totalAmount,
        List<Item> items,
        Instant createdAt,
        Instant updatedAt) {

    public record Item(UUID reservationId, long productId, int quantity, BigDecimal price) {

        static Item from(OrderItem item) {
            return new Item(item.getReservationId(), item.getProductId(), item.getQuantity(), item.getPrice());
        }
    }

    public static OrderResponse from(Order order) {
        return new OrderResponse(order.getOrderId(), order.getUserId(), order.getStatus(), order.getTotalAmount(),
                order.getItems().stream().map(Item::from).toList(), order.getCreatedAt(), order.getUpdatedAt());
    }
}
