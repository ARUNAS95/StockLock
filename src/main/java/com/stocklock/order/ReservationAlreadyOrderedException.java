package com.stocklock.order;

import com.stocklock.common.error.DomainException;
import com.stocklock.common.error.ErrorCode;
import java.util.UUID;

public class ReservationAlreadyOrderedException extends DomainException {

    public ReservationAlreadyOrderedException(UUID reservationId) {
        super(ErrorCode.RESERVATION_ALREADY_ORDERED,
                "Reservation %s already belongs to an order".formatted(reservationId));
    }
}
