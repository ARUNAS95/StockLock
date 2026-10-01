package com.stocklock.distributed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.stocklock.support.AppInstance;
import com.stocklock.support.StockSnapshot;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Instance A reserves stock and goes away before the client confirms and before its outbox events
 * are published. Instance B, which never saw the request, must release the stock and publish A's
 * events.
 *
 * <p>"Goes away" is modelled by closing A's application context. StockLock keeps no business state
 * in memory: everything A did is either committed in PostgreSQL or never happened, so the database
 * state B sees is the same as after a kill -9 (the docker-compose demo kills a real container).
 */
class InstanceFailureRecoveryIT {

    @Test
    void survivingInstanceReleasesStockAndPublishesEventsOfACrashedInstance() {
        String reservationId;
        long productId;
        JdbcClient jdbc;
        try (AppInstance a = AppInstance.start("instance-a", Map.of("stocklock.reservation.ttl", "PT2S"))) {
            jdbc = a.bean(JdbcClient.class);
            productId = a.api().createProduct(10);
            reservationId = a.api().reserveOrFail(productId, "alice", 3);
            assertThat(StockSnapshot.of(jdbc, productId).available()).isEqualTo(7);
            assertThat(outboxStatus(jdbc, reservationId)).isEqualTo("PENDING");
        } // instance A is gone: nobody will confirm, cancel or publish on its behalf

        try (AppInstance b = AppInstance.start("instance-b", Map.of(
                "stocklock.scheduling.enabled", true,
                "stocklock.reservation.expiration-interval", "PT0.2S",
                "stocklock.outbox.poll-interval", "PT0.2S"))) {
            JdbcClient db = b.bean(JdbcClient.class);

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(b.api().getReservation(reservationId).text("status")).isEqualTo("EXPIRED");
                StockSnapshot stock = StockSnapshot.of(db, productId);
                assertThat(stock.available()).isEqualTo(10);
                assertThat(stock.reserved()).isZero();
            });
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(db.sql("SELECT status FROM outbox_events WHERE aggregate_id = :id")
                            .param("id", reservationId).query(String.class).list())
                            .containsOnly("PUBLISHED")
                            .hasSize(2)); // ReservationCreated (from A) and ReservationExpired (from B)
        }
    }

    private static String outboxStatus(JdbcClient jdbc, String reservationId) {
        return jdbc.sql("SELECT status FROM outbox_events WHERE aggregate_id = :id")
                .param("id", reservationId).query(String.class).single();
    }
}
