package com.stocklock.reservation;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Optional<Reservation> findByReservationId(UUID reservationId);

    /** SELECT ... FOR UPDATE: serializes all state changes of one reservation across instances. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.reservationId = :reservationId")
    Optional<Reservation> findByReservationIdForUpdate(@Param("reservationId") UUID reservationId);

    /** Locks several reservations in ascending id order (a fixed order, so two callers cannot deadlock). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.reservationId in :reservationIds order by r.id")
    List<Reservation> findAllByReservationIdForUpdate(@Param("reservationIds") Collection<UUID> reservationIds);

    /**
     * Claims a batch of overdue ACTIVE reservations. SKIP LOCKED lets sweepers on several instances
     * work in parallel on disjoint rows and skips rows a client is cancelling/confirming right now.
     * If another transaction changed a row's status before we lock it, PostgreSQL re-checks
     * {@code status = 'ACTIVE'} against the new row version and drops it from the result.
     */
    @Query(value = """
            SELECT *
              FROM reservations
             WHERE status = 'ACTIVE'
               AND expires_at <= :now
             ORDER BY expires_at
             LIMIT :limit
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Reservation> claimExpired(@Param("now") Instant now, @Param("limit") int limit);

    List<Reservation> findByProductId(long productId);
}
