package com.stocklock.outbox;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Claims due events for this publisher. {@code FOR UPDATE SKIP LOCKED} lets every instance run a
     * publisher at the same time: each one locks a disjoint set of rows and skips rows another
     * instance is currently publishing, instead of blocking on them or publishing them twice.
     */
    @Query(value = """
            SELECT *
              FROM outbox_events
             WHERE status = 'PENDING'
               AND next_attempt_at <= :now
             ORDER BY id
             LIMIT :limit
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> claimDue(@Param("now") Instant now, @Param("limit") int limit);

    long countByStatus(OutboxStatus status);

    List<OutboxEvent> findByAggregateIdOrderById(String aggregateId);
}
