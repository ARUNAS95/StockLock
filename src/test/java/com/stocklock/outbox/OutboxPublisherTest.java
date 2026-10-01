package com.stocklock.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stocklock.common.config.StockLockProperties;
import com.stocklock.common.metrics.StockLockMetrics;
import com.stocklock.events.EventTypes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class OutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    private final OutboxRepository repository = mock(OutboxRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        StockLockProperties properties = new StockLockProperties(
                new StockLockProperties.Reservation(Duration.ofMinutes(5), Duration.ofSeconds(5), 100),
                new StockLockProperties.Idempotency(Duration.ofHours(24), Duration.ofHours(1)),
                new StockLockProperties.Outbox(Duration.ofSeconds(1), 10, Duration.ofSeconds(1),
                        Duration.ofSeconds(1), Duration.ofSeconds(60)),
                new StockLockProperties.Kafka("reservations", "orders", 3),
                new StockLockProperties.Scheduling(false));
        publisher = new OutboxPublisher(repository, kafka, new OutboxRetryPolicy(properties),
                new ObjectMapper().registerModule(new JavaTimeModule()), new StockLockMetrics(registry), properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static OutboxEvent event(String aggregateId) {
        return OutboxEvent.pending(EventTypes.RESERVATION_CREATED, EventTypes.AGGREGATE_RESERVATION, aggregateId,
                "{\"quantity\":1}", "cid-1", NOW);
    }

    @Test
    void marksEventPublishedOnlyAfterBrokerAcknowledges() {
        OutboxEvent event = event("a-1");
        when(repository.claimDue(any(), anyInt())).thenReturn(List.of(event));
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        assertThat(publisher.publishDueEvents()).isEqualTo(1);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isEqualTo(NOW);
        assertThat(registry.get("outbox.published").counter().count()).isEqualTo(1);
    }

    @Test
    void failedSendKeepsEventPendingWithBackoffAndStopsTheBatch() {
        OutboxEvent first = event("a-1");
        OutboxEvent second = event("a-2");
        when(repository.claimDue(any(), anyInt())).thenReturn(List.of(first, second));
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThat(publisher.publishDueEvents()).isZero();

        assertThat(first.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(first.getRetryCount()).isEqualTo(1);
        assertThat(first.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(first.getLastError()).contains("broker down");
        // The second event was not attempted: one failure is enough to know the broker is unhealthy.
        assertThat(second.getRetryCount()).isZero();
        verify(kafka, times(1)).send(any(ProducerRecord.class));
        assertThat(registry.get("outbox.failed").counter().count()).isEqualTo(1);
    }

    @Test
    void sendsKeyedByAggregateWithEventHeaders() {
        OutboxEvent event = event("agg-42");
        when(repository.claimDue(any(), anyInt())).thenReturn(List.of(event));
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<ProducerRecord<String, String>> captor =
                org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        when(kafka.send(captor.capture())).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publishDueEvents();

        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.topic()).isEqualTo("reservations");
        assertThat(record.key()).isEqualTo("agg-42");
        assertThat(new String(record.headers().lastHeader(OutboxPublisher.HEADER_EVENT_ID).value()))
                .isEqualTo(event.getEventId().toString());
        assertThat(record.value()).contains("\"eventId\":\"" + event.getEventId() + "\"").contains("\"quantity\":1");
    }
}
