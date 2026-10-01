package com.stocklock.idempotency;

import com.stocklock.common.config.StockLockProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authoritative idempotency check, backed by PostgreSQL. The claim is written in the same
 * transaction as the resource it protects: if the operation fails and rolls back, the claim
 * disappears with it and the client can retry; if it commits, the key is permanently bound to the
 * resource that was created.
 */
@Service
public class IdempotencyService {

    private final IdempotencyRecordRepository repository;
    private final StockLockProperties properties;
    private final Clock clock;

    public IdempotencyService(IdempotencyRecordRepository repository, StockLockProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param newResourceId the id the caller will use if it wins the claim
     * @return {@link Claim#acquired()} if the caller must now create the resource, otherwise the id of
     *     the resource an earlier request with this key already created
     * @throws IdempotencyKeyReusedException if the key was used with a different request
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Claim claim(String key, String requestHash, String resourceType, UUID newResourceId) {
        Instant now = Instant.now(clock);
        int inserted = repository.insertIfAbsent(key, requestHash, resourceType, newResourceId, now,
                now.plus(properties.idempotency().ttl()));
        if (inserted == 1) {
            return new Claim(true, newResourceId);
        }
        IdempotencyRecord existing = repository.findByIdempotencyKey(key)
                .orElseThrow(() -> new IllegalStateException("Idempotency record vanished for key " + key));
        if (!existing.getRequestHash().equals(requestHash) || !existing.getResourceType().equals(resourceType)) {
            throw new IdempotencyKeyReusedException(key);
        }
        return new Claim(false, existing.getResourceId());
    }

    @Transactional
    public int deleteExpired(int limit) {
        return repository.deleteExpired(Instant.now(clock), limit);
    }

    public record Claim(boolean acquired, UUID resourceId) {
    }
}
