package com.stocklock.reservation;

import com.stocklock.common.error.InvalidStateTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A time-limited claim on stock. State changes go through the methods below, which enforce the
 * {@link ReservationStatus} state machine. Callers must hold a row lock (SELECT ... FOR UPDATE) on
 * the reservation while calling them so that the check and the write cannot interleave with another
 * transaction on another instance.
 */
@Entity
@Table(name = "reservations")
public class Reservation {

    private static final String ENTITY = "Reservation";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private UUID reservationId;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false, length = 64)
    private String userId;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReservationStatus status;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Reservation() {
    }

    public static Reservation create(UUID reservationId, long productId, String userId, int quantity, Instant now,
                                     Duration ttl) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        Reservation reservation = new Reservation();
        reservation.reservationId = reservationId;
        reservation.productId = productId;
        reservation.userId = userId;
        reservation.quantity = quantity;
        reservation.status = ReservationStatus.ACTIVE;
        reservation.expiresAt = now.plus(ttl);
        reservation.createdAt = now;
        reservation.updatedAt = now;
        return reservation;
    }

    /** The deadline is inclusive: at {@code expiresAt} the hold is already over. */
    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** Converts the hold into a sale. Refused once the deadline has passed, even if the sweeper has not run yet. */
    public void confirm(Instant now) {
        if (status == ReservationStatus.ACTIVE && isExpiredAt(now)) {
            throw new ReservationExpiredException(reservationId);
        }
        transitionTo(ReservationStatus.CONFIRMED, now);
    }

    public void cancel(Instant now) {
        transitionTo(ReservationStatus.CANCELLED, now);
    }

    public void expire(Instant now) {
        if (status == ReservationStatus.ACTIVE && !isExpiredAt(now)) {
            throw new IllegalStateException("Reservation %s is not due to expire until %s".formatted(reservationId,
                    expiresAt));
        }
        transitionTo(ReservationStatus.EXPIRED, now);
    }

    public boolean isActive() {
        return status == ReservationStatus.ACTIVE;
    }

    private void transitionTo(ReservationStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException(ENTITY, reservationId, status, target);
        }
        status = target;
        updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public Long getProductId() {
        return productId;
    }

    public String getUserId() {
        return userId;
    }

    public int getQuantity() {
        return quantity;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
