package com.stocklock.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocklock.support.AppInstance;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Load test: {@code mvn verify -Pload}. Excluded from the default build because it is slower and
 * its latency numbers depend on the machine; its correctness assertions are the same as elsewhere.
 */
@Tag("load")
class ReservationLoadIT {

    private static List<AppInstance> instances;

    @BeforeAll
    static void start() {
        instances = List.of(
                AppInstance.start("load-1", Map.of()),
                AppInstance.start("load-2", Map.of()),
                AppInstance.start("load-3", Map.of()));
    }

    @AfterAll
    static void stop() {
        instances.forEach(AppInstance::close);
    }

    @ParameterizedTest(name = "{0} concurrent requests, stock {1}, {2} instance(s)")
    @CsvSource({
            "100, 1, 1",
            "500, 50, 1",
            "1000, 100, 1",
            "100, 1, 3",
            "500, 50, 3",
            "1000, 100, 3"})
    void concurrentReservationsNeverOversell(int requests, int stock, int instanceCount) throws Exception {
        List<String> urls = instances.subList(0, instanceCount).stream()
                .map(i -> "http://localhost:" + i.port())
                .toList();

        LoadTest.Result result = new LoadTest(urls).run(requests, stock);
        System.out.println("[load]\n" + result.report());

        assertThat(result.otherStatuses()).isEmpty();
        assertThat(result.succeeded()).isEqualTo(stock);
        assertThat(result.outOfStock()).isEqualTo(requests - stock);
        assertThat(result.finalAvailable()).isZero();
        assertThat(result.finalReserved()).isEqualTo(stock);
        assertThat(result.inventoryCorrect()).isTrue();
    }
}
