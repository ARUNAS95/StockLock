package com.stocklock.events;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * The Kafka message value. {@code eventId} is generated once, when the outbox row is written, and
 * survives every re-publish of that row; consumers de-duplicate on it.
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        Instant occurredAt,
        String correlationId,
        JsonNode payload) {
}
