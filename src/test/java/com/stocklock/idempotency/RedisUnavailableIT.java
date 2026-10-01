package com.stocklock.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocklock.support.Concurrently;
import com.stocklock.support.StockLockClient;
import com.stocklock.support.StockLockClient.Response;
import com.stocklock.support.StockSnapshot;
import com.stocklock.support.TestContainers;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Redis is unreachable for this whole context. Correctness must come from PostgreSQL alone. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@DirtiesContext
class RedisUnavailableIT {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    private StockLockClient api;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestContainers.register(registry);
        registry.add("spring.data.redis.port", TestContainers::closedPort);
    }

    @BeforeEach
    void setUp() {
        api = new StockLockClient(port);
    }

    @Test
    void reservationsStillWork() {
        long productId = api.createProduct(5);

        assertThat(api.reserve(productId, "alice", 2).status()).isEqualTo(201);
        assertThat(StockSnapshot.of(jdbc, productId).available()).isEqualTo(3);
        assertThat(meterRegistry.get("idempotency.cache.errors").counter().count()).isPositive();
    }

    @Test
    void idempotencyIsEnforcedByPostgres() {
        long productId = api.createProduct(5);
        String key = UUID.randomUUID().toString();

        List<Response> responses = Concurrently.run(20, i -> api.reserve(productId, "alice", 1, key));

        assertThat(responses).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(responses).extracting(r -> r.text("reservationId")).containsOnly(responses.get(0).text("reservationId"));
        assertThat(StockSnapshot.of(jdbc, productId).totalReservations()).isEqualTo(1);
    }

    @Test
    void concurrencyGuaranteeHoldsWithoutRedis() {
        long productId = api.createProduct(1);

        List<Integer> statuses = Concurrently.run(100, i -> api.reserve(productId, "user-" + i, 1).status());

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(99);
        StockSnapshot stock = StockSnapshot.of(jdbc, productId);
        assertThat(stock.available()).isZero();
        assertThat(stock.reserved()).isEqualTo(1);
    }

    @Test
    void instanceStaysReadyWhileRedisIsDown() {
        assertThat(api.get("/actuator/health/readiness").status()).isEqualTo(200);
    }
}
