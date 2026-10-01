package com.stocklock.order;

/**
 * <pre>
 * CREATED ──► CONFIRMED
 *    └──────► CANCELLED
 * </pre>
 * Cancelling a CONFIRMED order would be a refund/return flow, which is out of scope.
 */
public enum OrderStatus {
    CREATED,
    CONFIRMED,
    CANCELLED;

    public boolean canTransitionTo(OrderStatus target) {
        return this == CREATED && (target == CONFIRMED || target == CANCELLED);
    }
}
