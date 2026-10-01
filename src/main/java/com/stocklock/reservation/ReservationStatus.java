package com.stocklock.reservation;

import java.util.Set;

/**
 * <pre>
 * ACTIVE ──► CONFIRMED
 *   ├─────► CANCELLED
 *   └─────► EXPIRED
 * </pre>
 * Every non-ACTIVE state is terminal. Because stock is only released/consumed when leaving ACTIVE,
 * "a reservation leaves ACTIVE at most once" is exactly "its stock is released at most once".
 */
public enum ReservationStatus {
    ACTIVE,
    CONFIRMED,
    EXPIRED,
    CANCELLED;

    public boolean canTransitionTo(ReservationStatus target) {
        return this == ACTIVE && Set.of(CONFIRMED, CANCELLED, EXPIRED).contains(target);
    }
}
