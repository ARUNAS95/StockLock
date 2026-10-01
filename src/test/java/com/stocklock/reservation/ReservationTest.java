package com.stocklock.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocklock.common.error.InvalidStateTransitionException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ReservationTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(5);

    private static Reservation active() {
        return Reservation.create(UUID.randomUUID(), 1L, "user-1", 2, NOW, TTL);
    }

    @Test
    void newReservationIsActiveAndExpiresAfterTtl() {
        Reservation reservation = active();

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(reservation.getExpiresAt()).isEqualTo(NOW.plus(TTL));
    }

    @Test
    void expiryDeadlineIsInclusive() {
        Reservation reservation = active();

        assertThat(reservation.isExpiredAt(NOW.plus(TTL).minusMillis(1))).isFalse();
        assertThat(reservation.isExpiredAt(NOW.plus(TTL))).isTrue();
    }

    @Test
    void activeCanBeConfirmedBeforeDeadline() {
        Reservation reservation = active();

        reservation.confirm(NOW.plusSeconds(60));

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.getUpdatedAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void confirmAfterDeadlineIsRejectedEvenIfSweeperHasNotRun() {
        Reservation reservation = active();

        assertThatThrownBy(() -> reservation.confirm(NOW.plus(TTL)))
                .isInstanceOf(ReservationExpiredException.class);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
    }

    @Test
    void activeCanBeCancelled() {
        Reservation reservation = active();

        reservation.cancel(NOW);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    void activeCanExpireOnlyAfterDeadline() {
        Reservation reservation = active();

        assertThatThrownBy(() -> reservation.expire(NOW.plusSeconds(10))).isInstanceOf(IllegalStateException.class);

        reservation.expire(NOW.plus(TTL));
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    void expiredCannotBeConfirmed() {
        Reservation reservation = active();
        reservation.expire(NOW.plus(TTL));

        assertThatThrownBy(() -> reservation.confirm(NOW.plus(TTL)))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("EXPIRED to CONFIRMED");
    }

    @Test
    void expiringTwiceIsRejectedSoStockCannotBeReleasedTwice() {
        Reservation reservation = active();
        reservation.expire(NOW.plus(TTL));

        assertThatThrownBy(() -> reservation.expire(NOW.plus(TTL)))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void cancelledCannotBeExpiredOrConfirmed() {
        Reservation reservation = active();
        reservation.cancel(NOW);

        assertThatThrownBy(() -> reservation.expire(NOW.plus(TTL))).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> reservation.confirm(NOW)).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> reservation.cancel(NOW)).isInstanceOf(InvalidStateTransitionException.class);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"CONFIRMED", "EXPIRED", "CANCELLED"})
    void terminalStatesAllowNoTransitions(ReservationStatus terminal) {
        for (ReservationStatus target : ReservationStatus.values()) {
            assertThat(terminal.canTransitionTo(target)).as("%s -> %s", terminal, target).isFalse();
        }
    }

    @Test
    void activeCanReachEveryTerminalStateButNotItself() {
        assertThat(ReservationStatus.ACTIVE.canTransitionTo(ReservationStatus.ACTIVE)).isFalse();
        assertThat(ReservationStatus.ACTIVE.canTransitionTo(ReservationStatus.CONFIRMED)).isTrue();
        assertThat(ReservationStatus.ACTIVE.canTransitionTo(ReservationStatus.CANCELLED)).isTrue();
        assertThat(ReservationStatus.ACTIVE.canTransitionTo(ReservationStatus.EXPIRED)).isTrue();
    }

    @Test
    void rejectsNonPositiveQuantity() {
        assertThatThrownBy(() -> Reservation.create(UUID.randomUUID(), 1L, "u", 0, NOW, TTL))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
