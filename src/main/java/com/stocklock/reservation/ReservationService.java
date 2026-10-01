package com.stocklock.reservation;

import com.stocklock.common.config.StockLockProperties;
import com.stocklock.common.error.DomainException;
import com.stocklock.common.error.ErrorCode;
import com.stocklock.common.error.InvalidStateTransitionException;
import com.stocklock.common.metrics.StockLockMetrics;
import com.stocklock.events.EventTypes;
import com.stocklock.idempotency.IdempotencyCache;
import com.stocklock.idempotency.IdempotencyKeyReusedException;
import com.stocklock.idempotency.IdempotencyService;
import com.stocklock.idempotency.RequestFingerprint;
import com.stocklock.inventory.InventoryService;
import com.stocklock.reservation.dto.CreateReservationRequest;
import com.stocklock.reservation.dto.ReservationResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReservationService {

    static final String RESOURCE_TYPE = "RESERVATION";
    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ReservationRepository reservationRepository;
    private final InventoryService inventoryService;
    private final IdempotencyService idempotencyService;
    private final IdempotencyCache idempotencyCache;
    private final ReservationEvents reservationEvents;
    private final StockLockMetrics metrics;
    private final StockLockProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public ReservationService(ReservationRepository reservationRepository, InventoryService inventoryService,
                              IdempotencyService idempotencyService, IdempotencyCache idempotencyCache,
                              ReservationEvents reservationEvents, StockLockMetrics metrics,
                              StockLockProperties properties, PlatformTransactionManager transactionManager,
                              Clock clock) {
        this.reservationRepository = reservationRepository;
        this.inventoryService = inventoryService;
        this.idempotencyService = idempotencyService;
        this.idempotencyCache = idempotencyCache;
        this.reservationEvents = reservationEvents;
        this.metrics = metrics;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Reserves stock exactly once per Idempotency-Key.
     *
     * <ol>
     *   <li>Redis fast path (outside any transaction): a retry of a committed request is answered
     *       from the cache. Skipped transparently if Redis is unavailable.</li>
     *   <li>One PostgreSQL transaction: claim the key, atomically decrement stock, insert the
     *       reservation, append the outbox event. Any failure rolls back all four.</li>
     *   <li>After commit: populate the cache and record metrics.</li>
     * </ol>
     */
    public ReservationOutcome reserve(CreateReservationRequest request, String idempotencyKey) {
        String requestHash = RequestFingerprint.of(request.productId(), request.userId(), request.quantity());
        Optional<ReservationOutcome> cached = replayFromCache(idempotencyKey, requestHash);
        if (cached.isPresent()) {
            metrics.idempotentReplay();
            return cached.get();
        }

        ReservationOutcome outcome;
        try {
            outcome = transactionTemplate.execute(status -> reserveInTransaction(request, idempotencyKey, requestHash));
        } catch (DomainException e) {
            metrics.reservationFailed(e.code().name());
            throw e;
        }

        idempotencyCache.remember(idempotencyKey, requestHash, outcome.reservation().reservationId());
        if (outcome.replayed()) {
            metrics.idempotentReplay();
        } else {
            metrics.reservationCreated();
            log.info("Reservation created: reservationId={} productId={} userId={} quantity={} expiresAt={}",
                    outcome.reservation().reservationId(), request.productId(), request.userId(), request.quantity(),
                    outcome.reservation().expiresAt());
        }
        return outcome;
    }

    private ReservationOutcome reserveInTransaction(CreateReservationRequest request, String idempotencyKey,
                                                    String requestHash) {
        IdempotencyService.Claim claim =
                idempotencyService.claim(idempotencyKey, requestHash, RESOURCE_TYPE, UUID.randomUUID());
        if (!claim.acquired()) {
            Reservation existing = reservationRepository.findByReservationId(claim.resourceId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency key %s points to missing reservation %s".formatted(idempotencyKey,
                                    claim.resourceId())));
            return new ReservationOutcome(ReservationResponse.from(existing), true);
        }

        // Throws OutOfStockException when the conditional UPDATE matches no row; that rolls back the
        // idempotency claim too, so a later retry with the same key gets a fresh attempt.
        inventoryService.reserve(request.productId(), request.quantity());

        Reservation reservation = reservationRepository.save(Reservation.create(claim.resourceId(),
                request.productId(), request.userId(), request.quantity(), Instant.now(clock),
                properties.reservation().ttl()));
        reservationEvents.record(EventTypes.RESERVATION_CREATED, reservation);
        return new ReservationOutcome(ReservationResponse.from(reservation), false);
    }

    private Optional<ReservationOutcome> replayFromCache(String idempotencyKey, String requestHash) {
        Optional<IdempotencyCache.Entry> entry = idempotencyCache.find(idempotencyKey);
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        if (!entry.get().requestHash().equals(requestHash)) {
            metrics.reservationFailed(ErrorCode.DUPLICATE_IDEMPOTENCY_KEY.name());
            throw new IdempotencyKeyReusedException(idempotencyKey);
        }
        return reservationRepository.findByReservationId(entry.get().resourceId())
                .map(reservation -> new ReservationOutcome(ReservationResponse.from(reservation), true));
    }

    @Transactional(readOnly = true)
    public ReservationResponse get(UUID reservationId) {
        return reservationRepository.findByReservationId(reservationId)
                .map(ReservationResponse::from)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
    }

    /**
     * Cancels an ACTIVE reservation and returns its stock. The row lock makes this safe against a
     * concurrent cancel, order confirmation or expiration on any instance: whichever transaction
     * locks first changes the status; the others then see a non-ACTIVE status and are rejected
     * before they touch inventory.
     */
    @Transactional
    public ReservationResponse cancel(UUID reservationId) {
        Reservation reservation = reservationRepository.findByReservationIdForUpdate(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        try {
            reservation.cancel(Instant.now(clock));
        } catch (InvalidStateTransitionException e) {
            metrics.reservationConflict();
            throw e;
        }
        inventoryService.release(Map.of(reservation.getProductId(), reservation.getQuantity()));
        reservationEvents.record(EventTypes.RESERVATION_CANCELLED, reservation);
        metrics.reservationsCancelled(1);
        log.info("Reservation cancelled: reservationId={} productId={} quantity={}", reservationId,
                reservation.getProductId(), reservation.getQuantity());
        return ReservationResponse.from(reservation);
    }
}
