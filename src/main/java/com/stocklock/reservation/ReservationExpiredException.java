package com.stocklock.reservation;

import com.stocklock.common.error.DomainException;
import com.stocklock.common.error.ErrorCode;
import java.util.UUID;

public class ReservationExpiredException extends DomainException {

    public ReservationExpiredException(UUID reservationId) {
        super(ErrorCode.RESERVATION_EXPIRED, "Reservation %s has expired".formatted(reservationId));
    }
}
