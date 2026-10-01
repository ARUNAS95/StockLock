package com.stocklock.outbox;

/**
 * There is deliberately no terminal FAILED state: the realistic cause of a failed publish is an
 * unreachable broker, which is transient. Failed events stay PENDING with an exponentially growing
 * {@code next_attempt_at}, so they are retried until Kafka accepts them and are never dropped.
 */
public enum OutboxStatus {
    PENDING,
    PUBLISHED
}
