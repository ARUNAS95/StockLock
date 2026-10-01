package com.stocklock.events;

/** Names used in {@link EventEnvelope#eventType()} and {@link EventEnvelope#aggregateType()}. */
public final class EventTypes {

    public static final String AGGREGATE_RESERVATION = "Reservation";
    public static final String AGGREGATE_ORDER = "Order";

    public static final String RESERVATION_CREATED = "ReservationCreated";
    public static final String RESERVATION_CANCELLED = "ReservationCancelled";
    public static final String RESERVATION_EXPIRED = "ReservationExpired";
    public static final String ORDER_CREATED = "OrderCreated";
    public static final String ORDER_CONFIRMED = "OrderConfirmed";
    public static final String ORDER_CANCELLED = "OrderCancelled";

    private EventTypes() {
    }
}
