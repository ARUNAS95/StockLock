package com.stocklock.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * All StockLock tunables in one place. Every value can be overridden through environment
 * variables (e.g. {@code STOCKLOCK_RESERVATION_TTL=10m}).
 */
@Validated
@ConfigurationProperties(prefix = "stocklock")
public record StockLockProperties(
        @Valid @NotNull Reservation reservation,
        @Valid @NotNull Idempotency idempotency,
        @Valid @NotNull Outbox outbox,
        @Valid @NotNull Kafka kafka,
        @Valid @NotNull Scheduling scheduling) {

    /**
     * @param ttl                  how long a reservation holds stock before it is released
     * @param expirationInterval   how often each instance sweeps for expired reservations
     * @param expirationBatchSize  reservations expired per transaction
     */
    public record Reservation(
            @NotNull Duration ttl,
            @NotNull Duration expirationInterval,
            @Min(1) int expirationBatchSize) {
    }

    /**
     * @param ttl             how long an Idempotency-Key is remembered (Postgres row and Redis entry)
     * @param cleanupInterval how often expired idempotency rows are deleted
     */
    public record Idempotency(@NotNull Duration ttl, @NotNull Duration cleanupInterval) {
    }

    /**
     * @param pollInterval   delay between publisher runs on each instance
     * @param batchSize      events claimed per publisher transaction
     * @param sendTimeout    how long to wait for a Kafka acknowledgement before treating the send as failed
     * @param initialBackoff delay before the first retry of a failed event
     * @param maxBackoff     cap for the exponential retry delay
     */
    public record Outbox(
            @NotNull Duration pollInterval,
            @Min(1) int batchSize,
            @NotNull Duration sendTimeout,
            @NotNull Duration initialBackoff,
            @NotNull Duration maxBackoff) {
    }

    public record Kafka(
            @NotBlank String reservationEventsTopic,
            @NotBlank String orderEventsTopic,
            @Min(1) int partitions) {
    }

    /** Lets tests drive background jobs deterministically instead of racing a timer. */
    public record Scheduling(boolean enabled) {
    }
}
