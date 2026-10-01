package com.stocklock.reservation;

import com.stocklock.common.config.StockLockProperties;
import com.stocklock.common.metrics.StockLockMetrics;
import com.stocklock.events.EventTypes;
import com.stocklock.inventory.InventoryService;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Releases stock held by reservations whose deadline passed. This is also the crash-recovery
 * mechanism: a reservation is committed together with its stock decrement, so if the instance that
 * created it dies (before or after answering the client), the reservation is still ACTIVE in the
 * database with a deadline, and any surviving instance's sweeper will release it.
 *
 * <p>Idempotent and safe to run concurrently on every instance: rows are claimed with
 * {@code FOR UPDATE SKIP LOCKED} filtered on {@code status = 'ACTIVE'}, so a reservation can only be
 * expired by one transaction, and stock is released in that same transaction.
 */
@Service
public class ReservationExpirationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpirationService.class);

    private final ReservationRepository reservationRepository;
    private final InventoryService inventoryService;
    private final ReservationEvents reservationEvents;
    private final StockLockMetrics metrics;
    private final StockLockProperties properties;
    private final Clock clock;

    public ReservationExpirationService(ReservationRepository reservationRepository,
                                        InventoryService inventoryService, ReservationEvents reservationEvents,
                                        StockLockMetrics metrics, StockLockProperties properties, Clock clock) {
        this.reservationRepository = reservationRepository;
        this.inventoryService = inventoryService;
        this.reservationEvents = reservationEvents;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    /** @return how many reservations this call expired (0 when nothing is due) */
    @Transactional
    public int expireDueBatch() {
        Instant now = Instant.now(clock);
        List<Reservation> due = reservationRepository.claimExpired(now, properties.reservation().expirationBatchSize());
        if (due.isEmpty()) {
            return 0;
        }
        Map<Long, Integer> quantityByProduct = new HashMap<>();
        for (Reservation reservation : due) {
            reservation.expire(now);
            quantityByProduct.merge(reservation.getProductId(), reservation.getQuantity(), Integer::sum);
            reservationEvents.record(EventTypes.RESERVATION_EXPIRED, reservation);
        }
        inventoryService.release(quantityByProduct);
        metrics.reservationsExpired(due.size());
        log.info("Expired {} reservations, released stock per product: {}", due.size(), quantityByProduct);
        return due.size();
    }

    public int batchSize() {
        return properties.reservation().expirationBatchSize();
    }
}
