package com.stocklock.support;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Inventory counters read straight from PostgreSQL next to what the reservations table says they
 * should be. Used to assert the core invariant: reserved_quantity == sum of ACTIVE reservations.
 */
public record StockSnapshot(
        int available,
        int reserved,
        int activeReservedQuantity,
        int confirmedQuantity,
        long activeReservations,
        long totalReservations) {

    public static StockSnapshot of(JdbcClient jdbc, long productId) {
        return jdbc.sql("""
                        SELECT i.available_quantity,
                               i.reserved_quantity,
                               COALESCE(SUM(r.quantity) FILTER (WHERE r.status = 'ACTIVE'), 0),
                               COALESCE(SUM(r.quantity) FILTER (WHERE r.status = 'CONFIRMED'), 0),
                               COUNT(r.id) FILTER (WHERE r.status = 'ACTIVE'),
                               COUNT(r.id)
                          FROM inventory i
                          LEFT JOIN reservations r ON r.product_id = i.product_id
                         WHERE i.product_id = :productId
                         GROUP BY i.available_quantity, i.reserved_quantity
                        """)
                .param("productId", productId)
                .query((rs, row) -> new StockSnapshot(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                        rs.getLong(5), rs.getLong(6)))
                .single();
    }

    public boolean reservedMatchesActiveReservations() {
        return reserved == activeReservedQuantity;
    }
}
