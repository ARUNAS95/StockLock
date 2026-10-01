package com.stocklock.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByIdempotencyKey(String idempotencyKey);

    /**
     * Returns 1 if this transaction now owns the key, 0 if a committed record already exists.
     * If another in-flight transaction inserted the same key, PostgreSQL makes this statement wait
     * for that transaction: on its commit we get 0, on its rollback our insert proceeds and we get 1.
     * Concurrent duplicates are thereby serialized by the unique index, with no application lock.
     */
    @Modifying
    @Query(value = """
            INSERT INTO idempotency_records
                   (idempotency_key, request_hash, resource_type, resource_id, created_at, expires_at)
            VALUES (:key, :requestHash, :resourceType, :resourceId, :now, :expiresAt)
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("key") String key, @Param("requestHash") String requestHash,
                       @Param("resourceType") String resourceType, @Param("resourceId") UUID resourceId,
                       @Param("now") Instant now, @Param("expiresAt") Instant expiresAt);

    @Modifying
    @Query(value = """
            DELETE FROM idempotency_records
             WHERE id IN (SELECT id FROM idempotency_records WHERE expires_at <= :now LIMIT :limit)
            """, nativeQuery = true)
    int deleteExpired(@Param("now") Instant now, @Param("limit") int limit);
}
