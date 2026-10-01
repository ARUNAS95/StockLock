package com.stocklock.outbox;

import com.stocklock.common.config.StockLockProperties;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Exponential backoff (initial, 2x, 4x, ...) capped at {@code maxBackoff}. */
@Component
public class OutboxRetryPolicy {

    private final Duration initialBackoff;
    private final Duration maxBackoff;

    @Autowired
    public OutboxRetryPolicy(StockLockProperties properties) {
        this(properties.outbox().initialBackoff(), properties.outbox().maxBackoff());
    }

    OutboxRetryPolicy(Duration initialBackoff, Duration maxBackoff) {
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
    }

    /** @param attempt the number of failed attempts so far, starting at 1 */
    public Duration delayAfter(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1");
        }
        int exponent = Math.min(attempt - 1, 30); // keeps the shift from overflowing
        long millis = initialBackoff.toMillis() << exponent;
        return millis <= 0 || millis > maxBackoff.toMillis() ? maxBackoff : Duration.ofMillis(millis);
    }
}
