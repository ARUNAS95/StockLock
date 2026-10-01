package com.stocklock.distributed;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocklock.support.AppInstance;
import com.stocklock.support.Concurrently;
import com.stocklock.support.StockLockClient.Response;
import com.stocklock.support.StockSnapshot;
import com.stocklock.reservation.ReservationExpirationService;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Three independent application instances against one database. Any JVM-local locking
 * (synchronized, ReentrantLock, ConcurrentHashMap) would be per instance and could not prevent the
 * other two from overselling; these tests pass because correctness lives in PostgreSQL.
 */
class MultiInstanceIT {

    private static List<AppInstance> instances;
    private static JdbcClient jdbc;

    @BeforeAll
    static void startInstances() {
        instances = List.of(
                AppInstance.start("instance-1", Map.of()),
                AppInstance.start("instance-2", Map.of()),
                AppInstance.start("instance-3", Map.of("stocklock.reservation.ttl", "PT1S")));
        jdbc = instances.getFirst().bean(JdbcClient.class);
    }

    @AfterAll
    static void stopInstances() {
        instances.forEach(AppInstance::close);
    }

    private static AppInstance instanceFor(int requestIndex) {
        return instances.get(requestIndex % instances.size());
    }

    @Test
    void lastItemIsSoldOnceAcrossThreeInstances() {
        long productId = instances.getFirst().api().createProduct(1);

        List<String> outcomes = Concurrently.run(100, i -> {
            AppInstance instance = instanceFor(i);
            return instance.name() + ":" + instance.api().reserve(productId, "user-" + i, 1).status();
        });

        Map<String, Long> byInstanceAndStatus = new TreeMap<>();
        outcomes.forEach(o -> byInstanceAndStatus.merge(o, 1L, Long::sum));
        StockSnapshot stock = StockSnapshot.of(jdbc, productId);
        System.out.printf("[multi-instance] requests=100 inventory=1 outcomes=%s available=%d reserved=%d%n",
                byInstanceAndStatus, stock.available(), stock.reserved());

        assertThat(outcomes).filteredOn(o -> o.endsWith(":201")).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o.endsWith(":409")).hasSize(99);
        assertThat(stock.available()).isZero();
        assertThat(stock.reserved()).isEqualTo(1);
        assertThat(stock.totalReservations()).isEqualTo(1);
    }

    @Test
    void stockIsSharedExactlyAcrossInstances() {
        long productId = instances.getFirst().api().createProduct(60);

        List<Integer> statuses = Concurrently.run(600, i -> instanceFor(i).api().reserve(productId, "u" + i, 1).status());

        StockSnapshot stock = StockSnapshot.of(jdbc, productId);
        System.out.printf("[multi-instance] requests=600 inventory=60 -> 201:%d 409:%d available=%d reserved=%d%n",
                statuses.stream().filter(s -> s == 201).count(), statuses.stream().filter(s -> s == 409).count(),
                stock.available(), stock.reserved());
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(60);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(540);
        assertThat(stock.available()).isZero();
        assertThat(stock.reservedMatchesActiveReservations()).isTrue();
    }

    @Test
    void sameIdempotencyKeyOnDifferentInstancesCreatesOneReservation() {
        long productId = instances.getFirst().api().createProduct(10);
        String key = UUID.randomUUID().toString();

        List<Response> responses = Concurrently.run(30, i -> instanceFor(i).api().reserve(productId, "alice", 1, key));

        assertThat(responses).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(responses).extracting(Response::status).containsOnly(200, 201);
        assertThat(responses).extracting(r -> r.text("reservationId")).containsOnly(responses.getFirst().text("reservationId"));
        assertThat(StockSnapshot.of(jdbc, productId).available()).isEqualTo(9);
    }

    @Test
    void sweepersOnEveryInstanceReleaseExpiredStockOnce() throws Exception {
        long productId = instances.getFirst().api().createProduct(90);
        // Instance 3 creates reservations with a 1s TTL.
        Concurrently.run(90, i -> instances.get(2).api().reserveOrFail(productId, "u" + i, 1));
        Thread.sleep(1_500);

        Concurrently.run(instances.size(), i -> {
            ReservationExpirationService sweeper = instances.get(i).bean(ReservationExpirationService.class);
            while (sweeper.expireDueBatch() > 0) {
                // drain
            }
            return null;
        });

        StockSnapshot stock = StockSnapshot.of(jdbc, productId);
        assertThat(stock.available()).isEqualTo(90);
        assertThat(stock.reserved()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM reservations WHERE product_id = :p AND status = 'EXPIRED'")
                .param("p", productId).query(Long.class).single()).isEqualTo(90);
    }
}
