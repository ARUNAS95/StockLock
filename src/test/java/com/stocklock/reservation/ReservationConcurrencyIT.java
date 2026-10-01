package com.stocklock.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocklock.support.Concurrently;
import com.stocklock.support.IntegrationTest;
import com.stocklock.support.StockLockClient.Response;
import com.stocklock.support.StockSnapshot;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** The core guarantee: concurrent requests can never reserve more stock than exists. */
class ReservationConcurrencyIT extends IntegrationTest {

    @Test
    void hundredConcurrentRequestsForTheLastItemProduceExactlyOneReservation() {
        long productId = api.createProduct(1);

        List<Integer> statuses = Concurrently.run(100, i -> api.reserve(productId, "user-" + i, 1).status());

        Map<Integer, Long> byStatus = countByStatus(statuses);
        StockSnapshot stock = stock(productId);
        System.out.printf("[concurrency] requests=100 inventory=1 -> 201:%d 409:%d other:%d | available=%d reserved=%d%n",
                byStatus.getOrDefault(201, 0L), byStatus.getOrDefault(409, 0L),
                100 - byStatus.getOrDefault(201, 0L) - byStatus.getOrDefault(409, 0L), stock.available(),
                stock.reserved());

        assertThat(byStatus).containsEntry(201, 1L).containsEntry(409, 99L).hasSize(2);
        assertThat(stock.available()).isZero();
        assertThat(stock.reserved()).isEqualTo(1);
        assertThat(stock.totalReservations()).isEqualTo(1);
        assertThat(stock.reservedMatchesActiveReservations()).isTrue();
    }

    @ParameterizedTest(name = "{0} requests for {1} units")
    @CsvSource({"500, 50", "300, 299"})
    void concurrentRequestsReserveExactlyTheAvailableStock(int requests, int inventory) {
        long productId = api.createProduct(inventory);

        List<Integer> statuses = Concurrently.run(requests, i -> api.reserve(productId, "user-" + i, 1).status());

        Map<Integer, Long> byStatus = countByStatus(statuses);
        StockSnapshot stock = stock(productId);
        System.out.printf("[concurrency] requests=%d inventory=%d -> 201:%d 409:%d | available=%d reserved=%d%n",
                requests, inventory, byStatus.getOrDefault(201, 0L), byStatus.getOrDefault(409, 0L),
                stock.available(), stock.reserved());

        assertThat(byStatus).containsEntry(201, (long) inventory).containsEntry(409, (long) (requests - inventory));
        assertThat(stock.available()).isZero();
        assertThat(stock.reserved()).isEqualTo(inventory);
        assertThat(stock.reservedMatchesActiveReservations()).isTrue();
    }

    @Test
    void mixedQuantitiesNeverOversell() {
        int inventory = 25;
        long productId = api.createProduct(inventory);

        // Quantities 1..3: some requests fail even though stock remains, but the total held never exceeds 25.
        Concurrently.run(200, i -> api.reserve(productId, "user-" + i, 1 + i % 3).status());

        StockSnapshot stock = stock(productId);
        assertThat(stock.available()).isGreaterThanOrEqualTo(0);
        assertThat(stock.available() + stock.reserved()).isEqualTo(inventory);
        assertThat(stock.reservedMatchesActiveReservations()).isTrue();
    }

    @Test
    void concurrentRetriesWithTheSameKeyCreateOneReservation() {
        long productId = api.createProduct(10);
        String key = UUID.randomUUID().toString();

        List<Response> responses = Concurrently.run(50, i -> api.reserve(productId, "alice", 1, key));

        assertThat(responses).extracting(Response::status).containsOnly(200, 201);
        assertThat(responses).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(responses).extracting(r -> r.text("reservationId")).containsOnly(responses.get(0).text("reservationId"));
        assertThat(stock(productId).totalReservations()).isEqualTo(1);
        assertThat(stock(productId).available()).isEqualTo(9);
    }

    private static Map<Integer, Long> countByStatus(List<Integer> statuses) {
        return statuses.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }
}
