package com.stocklock.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocklock.support.IntegrationTest;
import com.stocklock.support.StockLockClient.Response;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReservationApiIT extends IntegrationTest {

    @Test
    void reservingMovesStockFromAvailableToReserved() {
        long productId = api.createProduct(10);

        Response created = api.reserve(productId, "alice", 3);

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.text("status")).isEqualTo("ACTIVE");
        assertThat(created.header("Location")).isEqualTo("/api/reservations/" + created.text("reservationId"));
        assertThat(api.inventory(productId).get("availableQuantity").asInt()).isEqualTo(7);
        assertThat(api.inventory(productId).get("reservedQuantity").asInt()).isEqualTo(3);
        assertThat(api.getReservation(created.text("reservationId")).text("userId")).isEqualTo("alice");
        assertThat(stock(productId).reservedMatchesActiveReservations()).isTrue();
    }

    @Test
    void outOfStockReturnsStructuredConflictAndChangesNothing() {
        long productId = api.createProduct(2);

        Response rejected = api.reserve(productId, "alice", 3);

        assertThat(rejected.status()).isEqualTo(409);
        assertThat(rejected.text("code")).isEqualTo("OUT_OF_STOCK");
        assertThat(rejected.text("message")).startsWith("Insufficient inventory for product SKU-");
        assertThat(rejected.text("path")).isEqualTo("/api/reservations");
        assertThat(stock(productId).available()).isEqualTo(2);
        assertThat(stock(productId).totalReservations()).isZero();
    }

    @Test
    void reservingUnknownProductIs404() {
        assertThat(api.reserve(999_999_999L, "alice", 1).text("code")).isEqualTo("PRODUCT_NOT_FOUND");
    }

    @Test
    void sameIdempotencyKeyReturnsTheOriginalReservation() {
        long productId = api.createProduct(10);
        String key = UUID.randomUUID().toString();

        Response first = api.reserve(productId, "alice", 2, key);
        Response second = api.reserve(productId, "alice", 2, key);
        Response third = api.reserve(productId, "alice", 2, key);

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.text("reservationId")).isEqualTo(first.text("reservationId"));
        assertThat(third.text("reservationId")).isEqualTo(first.text("reservationId"));
        assertThat(stock(productId).totalReservations()).isEqualTo(1);
        assertThat(stock(productId).available()).isEqualTo(8);
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejected() {
        long productId = api.createProduct(10);
        String key = UUID.randomUUID().toString();
        api.reserve(productId, "alice", 1, key);

        Response reused = api.reserve(productId, "alice", 5, key);

        assertThat(reused.status()).isEqualTo(422);
        assertThat(reused.text("code")).isEqualTo("DUPLICATE_IDEMPOTENCY_KEY");
        assertThat(stock(productId).totalReservations()).isEqualTo(1);
    }

    @Test
    void replayIsServedFromPostgresWhenRedisEntryIsMissing() {
        long productId = api.createProduct(10);
        String key = UUID.randomUUID().toString();
        Response first = api.reserve(productId, "alice", 1, key);
        flushRedis();

        Response second = api.reserve(productId, "alice", 1, key);

        assertThat(second.status()).isEqualTo(200);
        assertThat(second.text("reservationId")).isEqualTo(first.text("reservationId"));
        assertThat(stock(productId).totalReservations()).isEqualTo(1);
    }

    @Test
    void failedAttemptDoesNotBurnTheIdempotencyKey() {
        long productId = api.createProduct(1);
        String key = UUID.randomUUID().toString();
        String blocker = api.reserveOrFail(productId, "bob", 1);

        assertThat(api.reserve(productId, "alice", 1, key).status()).isEqualTo(409);
        api.cancelReservation(blocker);

        // The OUT_OF_STOCK attempt rolled back its idempotency claim, so the retry is a fresh attempt.
        assertThat(api.reserve(productId, "alice", 1, key).status()).isEqualTo(201);
    }

    @Test
    void missingIdempotencyKeyIsRejected() {
        long productId = api.createProduct(1);

        Response response = api.post("/api/reservations",
                Map.of("productId", productId, "userId", "alice", "quantity", 1), Map.of());

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.text("code")).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void cancelReleasesStockExactlyOnce() {
        long productId = api.createProduct(5);
        String reservationId = api.reserveOrFail(productId, "alice", 2);

        Response cancelled = api.cancelReservation(reservationId);
        Response again = api.cancelReservation(reservationId);

        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.text("status")).isEqualTo("CANCELLED");
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.text("code")).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(stock(productId).available()).isEqualTo(5);
        assertThat(stock(productId).reserved()).isZero();
    }

    @Test
    void unknownReservationIs404() {
        Response response = api.getReservation(UUID.randomUUID().toString());

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.text("code")).isEqualTo("RESERVATION_NOT_FOUND");
        assertThat(api.cancelReservation(UUID.randomUUID().toString()).text("code"))
                .isEqualTo("RESERVATION_NOT_FOUND");
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redis;

    private void flushRedis() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }
}
