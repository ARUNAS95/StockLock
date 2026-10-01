package com.stocklock.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocklock.common.web.CorrelationId;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records an event in the same database transaction as the state change it describes. Either both
 * commit or neither does, so there is no window in which the database says "reserved" but the
 * event was lost, or the event was sent for a change that was rolled back.
 */
@Component
public class OutboxWriter {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxWriter(OutboxRepository outboxRepository, ObjectMapper objectMapper, Clock clock) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String aggregateType, String aggregateId, String eventType, Object payload) {
        outboxRepository.save(OutboxEvent.pending(eventType, aggregateType, aggregateId, toJson(payload),
                CorrelationId.current(), Instant.now(clock)));
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Event payload is not serializable: " + payload.getClass(), e);
        }
    }
}
