package com.stocklock.events;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumer-side de-duplication. Must run in the same transaction as the consumer's side effect:
 * the side effect and the "processed" marker then commit or roll back together, so an event is
 * neither applied twice (marker committed) nor lost (side effect rolled back → marker rolled back →
 * Kafka redelivers).
 */
@Component
public class ProcessedEvents {

    private final JdbcClient jdbcClient;
    private final Clock clock;

    public ProcessedEvents(JdbcClient jdbcClient, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
    }

    /**
     * @return true if this is the first time {@code consumer} sees {@code eventId}. A concurrent
     *     duplicate blocks on the primary key until the first transaction finishes, then gets false.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(UUID eventId, String consumer) {
        return jdbcClient.sql("""
                        INSERT INTO processed_events (event_id, consumer, processed_at)
                        VALUES (:eventId, :consumer, :now)
                        ON CONFLICT DO NOTHING
                        """)
                .param("eventId", eventId)
                .param("consumer", consumer)
                .param("now", Timestamp.from(Instant.now(clock)))
                .update() == 1;
    }
}
