package com.stocklock.order;

import com.stocklock.common.error.DomainException;
import com.stocklock.common.error.ErrorCode;
import java.util.UUID;

public class ReservationOwnerMismatchException extends DomainException {

    public ReservationOwnerMismatchException(UUID reservationId, String userId) {
        super(ErrorCode.RESERVATION_OWNER_MISMATCH,
                "Reservation %s does not belong to user %s".formatted(reservationId, userId));
    }
}
