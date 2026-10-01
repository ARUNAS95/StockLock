package com.stocklock.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocklock.support.StockLockClient;
import com.stocklock.support.StockSnapshot;
import com.stocklock.support.TestContainers;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Kafka is down for this whole application context; the business transaction must not care. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.producer.properties.max.block.ms=1000"})
@ActiveProfiles("it")
@DirtiesContext
class KafkaUnavailableIT {

    @LocalServerPort
    private int port;

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private JdbcClient jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestContainers.register(registry);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:" + TestContainers.closedPort());
    }

    @Test
    void reservationsAndOrdersSucceedAndEventsWaitInTheOutbox() {
        StockLockClient api = new StockLockClient(port);
        long productId = api.createProduct(5);

        long start = System.nanoTime();
        String reservationId = api.reserveOrFail(productId, "alice", 2);
        long reserveMillis = (System.nanoTime() - start) / 1_000_000;
        String orderId = api.createOrder("alice", reservationId).text("orderId");
        assertThat(api.confirmOrder(orderId).status()).isEqualTo(200);

        assertThat(reserveMillis).as("Kafka is not on the request path").isLessThan(1000);
        assertThat(StockSnapshot.of(jdbc, productId).confirmedQuantity()).isEqualTo(2);

        int published = publisher.publishDueEvents();

        assertThat(published).isZero();
        List<OutboxEvent> events = outboxRepository.findByAggregateIdOrderById(reservationId);
        assertThat(events).isNotEmpty().allSatisfy(e -> assertThat(e.getStatus()).isEqualTo(OutboxStatus.PENDING));
        long failedAttempts = jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE retry_count > 0 AND status = 'PENDING'")
                .query(Long.class).single();
        assertThat(failedAttempts).as("the attempt was recorded for retry").isPositive();
    }

    @Test
    void instanceStaysReadyWhileKafkaIsDown() {
        StockLockClient api = new StockLockClient(port);

        assertThat(api.get("/actuator/health/readiness").status()).isEqualTo(200);
    }
}
