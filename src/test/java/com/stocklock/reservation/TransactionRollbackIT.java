package com.stocklock.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import com.stocklock.events.EventTypes;
import com.stocklock.reservation.dto.CreateReservationRequest;
import com.stocklock.support.IntegrationTest;
import com.stocklock.support.StockSnapshot;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The inventory decrement, reservation insert, idempotency claim and outbox event are one unit:
 * when any later step fails, the earlier ones must not stay committed.
 */
class TransactionRollbackIT extends IntegrationTest {

    @Autowired
    private ReservationService reservationService;

    @MockitoSpyBean
    private ReservationEvents reservationEvents;

    @Test
    void reservationInsertFailureRollsBackTheInventoryUpdate() {
        long productId = api.createProduct(5);
        String key = UUID.randomUUID().toString();
        // Bypasses API validation on purpose: user_id is VARCHAR(64), so the INSERT fails in PostgreSQL
        // *after* the inventory UPDATE has already been executed in the same transaction.
        CreateReservationRequest tooLongUser = new CreateReservationRequest(productId, "u".repeat(65), 2);

        assertThatThrownBy(() -> reservationService.reserve(tooLongUser, key))
                .isInstanceOf(DataIntegrityViolationException.class);

        StockSnapshot stock = stock(productId);
        assertThat(stock.available()).isEqualTo(5);
        assertThat(stock.reserved()).isZero();
        assertThat(stock.totalReservations()).isZero();
        assertThat(idempotencyRecords(key)).isZero();
    }

    @Test
    void outboxFailureRollsBackReservationAndInventory() {
        long productId = api.createProduct(5);
        String key = UUID.randomUUID().toString();
        doThrow(new IllegalStateException("simulated outbox failure"))
                .when(reservationEvents).record(eq(EventTypes.RESERVATION_CREATED), any());
        try {
            assertThat(api.reserve(productId, "alice", 2, key).status()).isEqualTo(500);
        } finally {
            org.mockito.Mockito.reset(reservationEvents);
        }

        StockSnapshot stock = stock(productId);
        assertThat(stock.available()).isEqualTo(5);
        assertThat(stock.totalReservations()).isZero();
        assertThat(idempotencyRecords(key)).isZero();

        // The key was not burned: the retry succeeds once the fault is gone.
        assertThat(api.reserve(productId, "alice", 2, key).status()).isEqualTo(201);
        assertThat(stock(productId).available()).isEqualTo(3);
    }

    @Test
    void cancelFailureKeepsReservationActiveAndStockReserved() {
        long productId = api.createProduct(5);
        String reservationId = api.reserveOrFail(productId, "alice", 2);
        doThrow(new IllegalStateException("simulated outbox failure"))
                .when(reservationEvents).record(eq(EventTypes.RESERVATION_CANCELLED), any());
        try {
            assertThat(api.cancelReservation(reservationId).status()).isEqualTo(500);
        } finally {
            org.mockito.Mockito.reset(reservationEvents);
        }

        assertThat(api.getReservation(reservationId).text("status")).isEqualTo("ACTIVE");
        assertThat(stock(productId).available()).isEqualTo(3);
        assertThat(stock(productId).reserved()).isEqualTo(2);
    }

    private long idempotencyRecords(String key) {
        return jdbc.sql("SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key = :key")
                .param("key", key).query(Long.class).single();
    }
}
