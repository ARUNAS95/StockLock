package com.stocklock.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class OutboxRetryPolicyTest {

    private final OutboxRetryPolicy policy = new OutboxRetryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(60));

    @Test
    void doublesDelayPerAttempt() {
        assertThat(policy.delayAfter(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.delayAfter(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delayAfter(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.delayAfter(6)).isEqualTo(Duration.ofSeconds(32));
    }

    @Test
    void capsAtMaxBackoffWithoutOverflow() {
        assertThat(policy.delayAfter(7)).isEqualTo(Duration.ofSeconds(60));
        assertThat(policy.delayAfter(1_000)).isEqualTo(Duration.ofSeconds(60));
    }
}
