package com.stocklock.reservation;

import com.stocklock.reservation.dto.ReservationResponse;

/** @param replayed true if the Idempotency-Key had already produced this reservation */
public record ReservationOutcome(ReservationResponse reservation, boolean replayed) {
}
