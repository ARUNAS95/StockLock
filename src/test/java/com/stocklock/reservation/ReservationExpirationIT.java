package com.stocklock.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocklock.common.scheduling.BackgroundJobs;
import com.stocklock.support.Concurrently;
import com.stocklock.support.IntegrationTest;
import com.stocklock.support.StockLockClient.Response;
import com.stocklock.support.StockSnapshot;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReservationExpirationIT extends IntegrationTest {

    private static final Duration PAST_TTL = Duration.ofMinutes(5).plusSeconds(1);

    @Autowired
    private ReservationExpirationService expirationService;

    @Autowired
    private BackgroundJobs backgroundJobs;

    @Test
    void unconfirmedReservationExpiresAndReturnsItsStock() {
        long productId = api.createProduct(10);
        String reservationId = api.reserveOrFail(productId, "alice", 3);
        assertThat(stock(productId).available()).isEqualTo(7);

        backgroundJobs.expireReservations();
        assertThat(stock(productId).available()).as("not due yet").isEqualTo(7);

        clock.advance(PAST_TTL);
        backgroundJobs.expireReservations();

        assertThat(api.getReservation(reservationId).text("status")).isEqualTo("EXPIRED");
        StockSnapshot stock = stock(productId);
        assertThat(stock.available()).isEqualTo(10);
        assertThat(stock.reserved()).isZero();
    }

    @Test
    void runningExpirationAgainIsANoOp() {
        long productId = api.createProduct(10);
        api.reserveOrFail(productId, "alice", 3);
        clock.advance(PAST_TTL);

        backgroundJobs.expireReservations();
        backgroundJobs.expireReservations();
        backgroundJobs.expireReservations();

        assertThat(stock(productId).available()).isEqualTo(10);
        assertThat(stock(productId).reserved()).isZero();
        assertThat(expiredEvents(productId)).isEqualTo(1);
    }

    @Test
    void concurrentSweepersReleaseEachReservationExactlyOnce() {
        int reservations = 250;
        long productId = api.createProduct(reservations);
        Concurrently.run(reservations, i -> api.reserveOrFail(productId, "user-" + i, 1));
        assertThat(stock(productId).available()).isZero();
        clock.advance(PAST_TTL);

        AtomicInteger expiredByAllWorkers = new AtomicInteger();
        Concurrently.run(4, worker -> {
            int expired;
            while ((expired = expirationService.expireDueBatch()) > 0) {
                expiredByAllWorkers.addAndGet(expired);
            }
            return null;
        });

        StockSnapshot stock = stock(productId);
        assertThat(stock.available()).isEqualTo(reservations);
        assertThat(stock.reserved()).isZero();
        assertThat(stock.activeReservations()).isZero();
        assertThat(expiredEvents(productId)).isEqualTo(reservations);
        assertThat(expiredByAllWorkers.get()).isGreaterThanOrEqualTo(reservations);
    }

    @RepeatedTest(10)
    void cancelRacingExpirationReleasesStockOnce() {
        long productId = api.createProduct(5);
        String reservationId = api.reserveOrFail(productId, "alice", 2);
        clock.advance(PAST_TTL);

        List<Object> results = Concurrently.run(2, i -> i == 0
                ? api.cancelReservation(reservationId).status()
                : expirationService.expireDueBatch());

        String finalStatus = api.getReservation(reservationId).text("status");
        assertThat(finalStatus).isIn("CANCELLED", "EXPIRED");
        assertThat((Integer) results.get(0)).isEqualTo(finalStatus.equals("CANCELLED") ? 200 : 409);
        StockSnapshot stock = stock(productId);
        assertThat(stock.available()).isEqualTo(5);
        assertThat(stock.reserved()).isZero();
    }

    @Test
    void reservationCannotBeOrderedAfterItsDeadline() {
        long productId = api.createProduct(5);
        String reservationId = api.reserveOrFail(productId, "alice", 1);
        clock.advance(PAST_TTL);

        Response response = api.createOrder("alice", reservationId);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.text("code")).isEqualTo("RESERVATION_EXPIRED");
    }

    private long expiredEvents(long productId) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM outbox_events
                         WHERE event_type = 'ReservationExpired' AND (payload ->> 'productId')::bigint = :productId
                        """)
                .param("productId", productId).query(Long.class).single();
    }
}
