package com.stocklock.reservation.dto;

import com.stocklock.reservation.Reservation;
import com.stocklock.reservation.ReservationStatus;
import java.time.Instant;
import java.util.UUID;

public record ReservationResponse(
        UUID reservationId,
        long productId,
        String userId,
        int quantity,
        ReservationStatus status,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {

    public static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(reservation.getReservationId(), reservation.getProductId(),
                reservation.getUserId(), reservation.getQuantity(), reservation.getStatus(),
                reservation.getExpiresAt(), reservation.getCreatedAt(), reservation.getUpdatedAt());
    }
}
