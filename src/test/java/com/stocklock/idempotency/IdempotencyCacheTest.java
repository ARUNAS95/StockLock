package com.stocklock.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stocklock.common.config.StockLockProperties;
import com.stocklock.common.metrics.StockLockMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class IdempotencyCacheTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final IdempotencyCache cache;

    IdempotencyCacheTest() {
        when(redis.opsForValue()).thenReturn(values);
        StockLockProperties properties = new StockLockProperties(
                new StockLockProperties.Reservation(Duration.ofMinutes(5), Duration.ofSeconds(5), 100),
                new StockLockProperties.Idempotency(Duration.ofHours(24), Duration.ofHours(1)),
                new StockLockProperties.Outbox(Duration.ofSeconds(1), 10, Duration.ofSeconds(1),
                        Duration.ofSeconds(1), Duration.ofSeconds(60)),
                new StockLockProperties.Kafka("r", "o", 3),
                new StockLockProperties.Scheduling(false));
        cache = new IdempotencyCache(redis, properties, new StockLockMetrics(registry));
    }

    @Test
    void roundTripsHashAndResourceId() {
        UUID id = UUID.randomUUID();
        when(values.get("stocklock:idempotency:k1")).thenReturn("abc123:" + id);

        assertThat(cache.find("k1")).contains(new IdempotencyCache.Entry("abc123", id));
    }

    @Test
    void redisReadFailureIsAMissNotAnError() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("connection refused"));

        assertThat(cache.find("k1")).isEmpty();
        assertThat(registry.get("idempotency.cache.errors").counter().count()).isEqualTo(1);
    }

    @Test
    void redisWriteFailureIsSwallowed() {
        doThrow(new RedisConnectionFailureException("connection refused"))
                .when(values).set(anyString(), anyString(), any(Duration.class));

        cache.remember("k1", "hash", UUID.randomUUID());

        assertThat(registry.get("idempotency.cache.errors").counter().count()).isEqualTo(1);
    }
}
