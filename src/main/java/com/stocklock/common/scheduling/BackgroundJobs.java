package com.stocklock.common.scheduling;

import com.stocklock.common.metrics.StockLockMetrics;
import com.stocklock.common.web.CorrelationId;
import com.stocklock.idempotency.IdempotencyService;
import com.stocklock.outbox.OutboxPublisher;
import com.stocklock.reservation.ReservationExpirationService;
import java.util.UUID;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * The periodic jobs every instance runs. None of them needs leader election: each one claims work
 * with row locks ({@code FOR UPDATE SKIP LOCKED}) or idempotent statements, so N instances running
 * the same job concurrently just share the work.
 *
 * <p>Each job drains its backlog in batches, one transaction per batch, so a large backlog never
 * becomes one long transaction holding many locks.
 */
@Component
public class BackgroundJobs {

    private static final Logger log = LoggerFactory.getLogger(BackgroundJobs.class);
    private static final int IDEMPOTENCY_CLEANUP_BATCH = 1000;
    private static final int MAX_BATCHES_PER_RUN = 100;

    private final ReservationExpirationService expirationService;
    private final OutboxPublisher outboxPublisher;
    private final IdempotencyService idempotencyService;
    private final StockLockMetrics metrics;

    public BackgroundJobs(ReservationExpirationService expirationService, OutboxPublisher outboxPublisher,
                          IdempotencyService idempotencyService, StockLockMetrics metrics) {
        this.expirationService = expirationService;
        this.outboxPublisher = outboxPublisher;
        this.idempotencyService = idempotencyService;
        this.metrics = metrics;
    }

    public void expireReservations() {
        run("reservation-expiration", () -> drain(expirationService::expireDueBatch, expirationService.batchSize()));
    }

    public void publishOutbox() {
        run("outbox-publisher", () -> {
            int published = drainUntilEmpty(outboxPublisher::publishDueEvents);
            metrics.outboxPending(outboxPublisher.countPending());
            return published;
        });
    }

    public void cleanupIdempotencyRecords() {
        run("idempotency-cleanup", () -> drain(() -> idempotencyService.deleteExpired(IDEMPOTENCY_CLEANUP_BATCH),
                IDEMPOTENCY_CLEANUP_BATCH));
    }

    /** Runs batches while they come back full. */
    private static int drain(IntSupplier batch, int batchSize) {
        int total = 0;
        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            int processed = batch.getAsInt();
            total += processed;
            if (processed < batchSize) {
                break;
            }
        }
        return total;
    }

    /** Runs batches while they make progress (the outbox batch stops early on a send failure). */
    private static int drainUntilEmpty(IntSupplier batch) {
        int total = 0;
        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            int processed = batch.getAsInt();
            total += processed;
            if (processed == 0) {
                break;
            }
        }
        return total;
    }

    /** Gives every job run its own correlation id so its log lines and emitted events can be traced. */
    private static void run(String job, IntSupplier body) {
        MDC.put(CorrelationId.CORRELATION_MDC_KEY, job + "-" + UUID.randomUUID());
        try {
            int processed = body.getAsInt();
            if (processed > 0) {
                log.debug("Job {} processed {} items", job, processed);
            }
        } catch (RuntimeException e) {
            // Swallow so the next scheduled run still happens; the work is retried then.
            log.error("Job {} failed; it will run again on the next tick", job, e);
        } finally {
            MDC.remove(CorrelationId.CORRELATION_MDC_KEY);
        }
    }
}
