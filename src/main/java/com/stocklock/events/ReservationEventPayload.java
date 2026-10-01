package com.stocklock.events;

import java.time.Instant;
import java.util.UUID;

public record ReservationEventPayload(
        UUID reservationId,
        long productId,
        String userId,
        int quantity,
        String status,
        Instant expiresAt) {
}
