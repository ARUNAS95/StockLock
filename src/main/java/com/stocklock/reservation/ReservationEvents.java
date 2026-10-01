package com.stocklock.reservation;

import com.stocklock.events.EventTypes;
import com.stocklock.events.ReservationEventPayload;
import com.stocklock.outbox.OutboxWriter;
import org.springframework.stereotype.Component;

/** Writes reservation events to the outbox (inside the caller's transaction). */
@Component
public class ReservationEvents {

    private final OutboxWriter outboxWriter;

    public ReservationEvents(OutboxWriter outboxWriter) {
        this.outboxWriter = outboxWriter;
    }

    public void record(String eventType, Reservation reservation) {
        outboxWriter.append(EventTypes.AGGREGATE_RESERVATION, reservation.getReservationId().toString(), eventType,
                new ReservationEventPayload(reservation.getReservationId(), reservation.getProductId(),
                        reservation.getUserId(), reservation.getQuantity(), reservation.getStatus().name(),
                        reservation.getExpiresAt()));
    }
}
