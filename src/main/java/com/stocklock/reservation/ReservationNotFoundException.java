package com.stocklock.reservation;

import com.stocklock.common.error.DomainException;
import com.stocklock.common.error.ErrorCode;
import java.util.UUID;

public class ReservationNotFoundException extends DomainException {

    public ReservationNotFoundException(UUID reservationId) {
        super(ErrorCode.RESERVATION_NOT_FOUND, "Reservation %s not found".formatted(reservationId));
    }
}
