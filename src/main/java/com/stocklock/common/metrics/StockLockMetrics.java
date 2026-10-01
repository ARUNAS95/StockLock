package com.stocklock.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Domain metrics. Success counters are only incremented after the surrounding transaction commits:
 * counting inside a transaction that later rolls back would report reservations that never existed.
 * Failure counters are incremented immediately because the failure itself is the event.
 */
@Component
public class StockLockMetrics {

    private final MeterRegistry registry;
    private final Counter reservationsCreated;
    private final Counter reservationsConfirmed;
    private final Counter reservationsCancelled;
    private final Counter reservationsExpired;
    private final Counter outOfStock;
    private final Counter reservationConflicts;
    private final Counter idempotentReplays;
    private final Counter idempotencyCacheErrors;
    private final Counter ordersCreated;
    private final Counter ordersConfirmed;
    private final Counter ordersCancelled;
    private final Counter outboxPublished;
    private final Counter outboxFailed;
    private final AtomicLong outboxPending = new AtomicLong();

    public StockLockMetrics(MeterRegistry registry) {
        this.registry = registry;
        reservationsCreated = counter("reservations.created", "Reservations committed (excludes idempotent replays)");
        reservationsConfirmed = counter("reservations.confirmed", "Reservations converted into a confirmed order");
        reservationsCancelled = counter("reservations.cancelled", "Reservations cancelled by a client or order cancellation");
        reservationsExpired = counter("reservations.expired", "Reservations released by the expiration sweeper");
        outOfStock = counter("inventory.out_of_stock", "Atomic reserve statements that matched no row because stock was insufficient");
        reservationConflicts = counter("inventory.reservation_conflicts",
                "Attempts to release or consume reserved stock for a reservation that was no longer ACTIVE (a prevented double release)");
        idempotentReplays = counter("idempotency.replays", "Requests answered from an existing Idempotency-Key (client retries)");
        idempotencyCacheErrors = counter("idempotency.cache.errors", "Redis failures that fell back to PostgreSQL");
        ordersCreated = counter("orders.created", "Orders created");
        ordersConfirmed = counter("orders.confirmed", "Orders confirmed");
        ordersCancelled = counter("orders.cancelled", "Orders cancelled");
        outboxPublished = counter("outbox.published", "Outbox events acknowledged by Kafka");
        outboxFailed = counter("outbox.failed", "Outbox publish attempts that failed and were scheduled for retry");
        Gauge.builder("outbox.pending", outboxPending, AtomicLong::get)
                .description("Outbox events not yet published; a growing value means Kafka is unreachable or slow")
                .register(registry);
    }

    public void reservationCreated() {
        afterCommit(reservationsCreated::increment);
    }

    public void reservationsConfirmed(int count) {
        afterCommit(() -> reservationsConfirmed.increment(count));
    }

    public void reservationsCancelled(int count) {
        afterCommit(() -> reservationsCancelled.increment(count));
    }

    public void reservationsExpired(int count) {
        afterCommit(() -> reservationsExpired.increment(count));
    }

    public void reservationFailed(String reason) {
        registry.counter("reservations.failed", "reason", reason).increment();
    }

    public void outOfStock() {
        outOfStock.increment();
    }

    public void reservationConflict() {
        reservationConflicts.increment();
    }

    public void idempotentReplay() {
        idempotentReplays.increment();
    }

    public void idempotencyCacheError() {
        idempotencyCacheErrors.increment();
    }

    public void orderCreated() {
        afterCommit(ordersCreated::increment);
    }

    public void orderConfirmed() {
        afterCommit(ordersConfirmed::increment);
    }

    public void orderCancelled() {
        afterCommit(ordersCancelled::increment);
    }

    public void outboxPublished(int count) {
        afterCommit(() -> outboxPublished.increment(count));
    }

    public void outboxFailed() {
        afterCommit(outboxFailed::increment);
    }

    public void outboxPending(long pending) {
        outboxPending.set(pending);
    }

    public void eventConsumed(String consumer, String outcome) {
        registry.counter("events.consumed", "consumer", consumer, "outcome", outcome).increment();
    }

    private Counter counter(String name, String description) {
        return Counter.builder(name).description(description).register(registry);
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
