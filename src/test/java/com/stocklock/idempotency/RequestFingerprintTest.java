package com.stocklock.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

    @Test
    void isDeterministicAndSensitiveToEveryField() {
        String base = RequestFingerprint.of(1L, "user", 2);

        assertThat(RequestFingerprint.of(1L, "user", 2)).isEqualTo(base).hasSize(64);
        assertThat(RequestFingerprint.of(2L, "user", 2)).isNotEqualTo(base);
        assertThat(RequestFingerprint.of(1L, "other", 2)).isNotEqualTo(base);
        assertThat(RequestFingerprint.of(1L, "user", 3)).isNotEqualTo(base);
    }

    @Test
    void fieldBoundariesCannotBeShifted() {
        assertThat(RequestFingerprint.of("ab", "c")).isNotEqualTo(RequestFingerprint.of("a", "bc"));
    }
}
