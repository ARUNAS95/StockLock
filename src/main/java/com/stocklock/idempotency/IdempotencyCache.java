package com.stocklock.idempotency;

import com.stocklock.common.config.StockLockProperties;
import com.stocklock.common.metrics.StockLockMetrics;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis read-through cache in front of {@link IdempotencyService}. A hit lets a client retry be
 * answered with a single read instead of a write transaction that would wait on the idempotency
 * index. It is strictly an optimisation:
 * <ul>
 *   <li>entries are only written after the PostgreSQL transaction committed, so the cache never
 *       knows about a resource the database does not;</li>
 *   <li>a miss, an eviction or Redis being down falls through to the PostgreSQL check, which is
 *       correct on its own. Redis errors are logged and counted, never propagated.</li>
 * </ul>
 */
@Component
public class IdempotencyCache {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCache.class);
    private static final String KEY_PREFIX = "stocklock:idempotency:";

    private final StringRedisTemplate redis;
    private final StockLockProperties properties;
    private final StockLockMetrics metrics;

    public IdempotencyCache(StringRedisTemplate redis, StockLockProperties properties, StockLockMetrics metrics) {
        this.redis = redis;
        this.properties = properties;
        this.metrics = metrics;
    }

    public Optional<Entry> find(String idempotencyKey) {
        try {
            String value = redis.opsForValue().get(KEY_PREFIX + idempotencyKey);
            return Optional.ofNullable(value).map(Entry::parse);
        } catch (RuntimeException e) {
            onRedisFailure("read", e);
            return Optional.empty();
        }
    }

    public void remember(String idempotencyKey, String requestHash, UUID resourceId) {
        try {
            redis.opsForValue().set(KEY_PREFIX + idempotencyKey, new Entry(requestHash, resourceId).serialize(),
                    properties.idempotency().ttl());
        } catch (RuntimeException e) {
            onRedisFailure("write", e);
        }
    }

    private void onRedisFailure(String operation, RuntimeException e) {
        metrics.idempotencyCacheError();
        log.warn("Redis idempotency cache {} failed, falling back to PostgreSQL: {}", operation, e.getMessage());
    }

    public record Entry(String requestHash, UUID resourceId) {

        static Entry parse(String value) {
            int separator = value.indexOf(':');
            return new Entry(value.substring(0, separator), UUID.fromString(value.substring(separator + 1)));
        }

        String serialize() {
            return requestHash + ":" + resourceId;
        }
    }
}
