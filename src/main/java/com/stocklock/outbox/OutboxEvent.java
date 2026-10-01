package com.stocklock.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    private static final int MAX_ERROR_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private UUID eventId;

    @Column(nullable = false, length = 64)
    private String eventType;

    @Column(nullable = false, length = 32)
    private String aggregateType;

    @Column(nullable = false, length = 64)
    private String aggregateId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OutboxStatus status;

    @Column(length = 64)
    private String correlationId;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant publishedAt;

    @Column(nullable = false)
    private int retryCount;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    private String lastError;

    protected OutboxEvent() {
    }

    public static OutboxEvent pending(String eventType, String aggregateType, String aggregateId, String payload,
                                      String correlationId, Instant now) {
        OutboxEvent event = new OutboxEvent();
        event.eventId = UUID.randomUUID();
        event.eventType = eventType;
        event.aggregateType = aggregateType;
        event.aggregateId = aggregateId;
        event.payload = payload;
        event.status = OutboxStatus.PENDING;
        event.correlationId = correlationId;
        event.createdAt = now;
        event.nextAttemptAt = now;
        return event;
    }

    public void markPublished(Instant now) {
        status = OutboxStatus.PUBLISHED;
        publishedAt = now;
        lastError = null;
    }

    public void markFailed(String error, Instant nextAttemptAt) {
        retryCount++;
        this.nextAttemptAt = nextAttemptAt;
        lastError = error == null ? "unknown" : error.substring(0, Math.min(error.length(), MAX_ERROR_LENGTH));
    }

    public Long getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }
}
