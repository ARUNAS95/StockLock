package com.stocklock.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocklock.common.config.StockLockProperties;
import com.stocklock.common.metrics.StockLockMetrics;
import com.stocklock.events.EventEnvelope;
import com.stocklock.events.EventTypes;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves committed outbox rows to Kafka.
 *
 * <p>Delivery is at-least-once, not exactly-once: if this instance dies after Kafka acknowledged a
 * send but before the {@code PUBLISHED} update commits, the row is still {@code PENDING} and will be
 * sent again. Consumers therefore de-duplicate on {@code eventId}.
 *
 * <p>The claim transaction stays open while sending so that the row locks keep other instances
 * away from the same events. That holds a database connection for up to
 * {@code batchSize x sendTimeout} in the worst case; both are bounded and configurable.
 */
@Component
public class OutboxPublisher {

    public static final String HEADER_EVENT_ID = "eventId";
    public static final String HEADER_EVENT_TYPE = "eventType";
    public static final String HEADER_CORRELATION_ID = "correlationId";

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxRetryPolicy retryPolicy;
    private final ObjectMapper objectMapper;
    private final StockLockMetrics metrics;
    private final StockLockProperties properties;
    private final Clock clock;

    public OutboxPublisher(OutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate,
                           OutboxRetryPolicy retryPolicy, ObjectMapper objectMapper, StockLockMetrics metrics,
                           StockLockProperties properties, Clock clock) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.retryPolicy = retryPolicy;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    /** @return the number of events published in this run */
    @Transactional
    public int publishDueEvents() {
        List<OutboxEvent> batch = outboxRepository.claimDue(Instant.now(clock), properties.outbox().batchSize());
        int published = 0;
        for (OutboxEvent event : batch) {
            try {
                send(event);
                event.markPublished(Instant.now(clock));
                published++;
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                recordFailure(event, e);
                // The broker is most likely unreachable; do not spend sendTimeout on every remaining
                // event. They stay PENDING untouched and are picked up by the next run.
                break;
            }
        }
        metrics.outboxPublished(published);
        if (published > 0) {
            log.debug("Published {} outbox events", published);
        }
        return published;
    }

    public long countPending() {
        return outboxRepository.countByStatus(OutboxStatus.PENDING);
    }

    private void send(OutboxEvent event)
            throws JsonProcessingException, ExecutionException, InterruptedException, TimeoutException {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(topicFor(event), event.getAggregateId(), toEnvelopeJson(event));
        record.headers().add(HEADER_EVENT_ID, bytes(event.getEventId().toString()));
        record.headers().add(HEADER_EVENT_TYPE, bytes(event.getEventType()));
        if (event.getCorrelationId() != null) {
            record.headers().add(HEADER_CORRELATION_ID, bytes(event.getCorrelationId()));
        }
        // Block for the broker acknowledgement (acks=all): only an acknowledged event may be marked PUBLISHED.
        kafkaTemplate.send(record).get(properties.outbox().sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void recordFailure(OutboxEvent event, Exception e) {
        Duration delay = retryPolicy.delayAfter(event.getRetryCount() + 1);
        String reason = rootMessage(e);
        event.markFailed(reason, Instant.now(clock).plus(delay));
        metrics.outboxFailed();
        log.warn("Outbox publish failed, will retry: eventId={} eventType={} attempt={} retryIn={} reason={}",
                event.getEventId(), event.getEventType(), event.getRetryCount(), delay, reason);
    }

    private String toEnvelopeJson(OutboxEvent event) throws JsonProcessingException {
        EventEnvelope envelope = new EventEnvelope(event.getEventId(), event.getEventType(), event.getAggregateType(),
                event.getAggregateId(), event.getCreatedAt(), event.getCorrelationId(),
                objectMapper.readTree(event.getPayload()));
        return objectMapper.writeValueAsString(envelope);
    }

    private String topicFor(OutboxEvent event) {
        return switch (event.getAggregateType()) {
            case EventTypes.AGGREGATE_RESERVATION -> properties.kafka().reservationEventsTopic();
            case EventTypes.AGGREGATE_ORDER -> properties.kafka().orderEventsTopic();
            default -> throw new IllegalStateException("No topic for aggregate " + event.getAggregateType());
        };
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
