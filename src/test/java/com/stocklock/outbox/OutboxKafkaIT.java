package com.stocklock.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocklock.common.config.StockLockProperties;
import com.stocklock.events.EventEnvelope;
import com.stocklock.sales.SalesProjection;
import com.stocklock.support.Concurrently;
import com.stocklock.support.IntegrationTest;
import com.stocklock.support.TestContainers;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Outbox → Kafka → idempotent consumer, against a real broker. */
class OutboxKafkaIT extends IntegrationTest {

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private SalesProjection salesProjection;

    @Autowired
    private StockLockProperties properties;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void drainBacklogFromOtherTests() {
        while (publisher.publishDueEvents() > 0) {
            // other test classes leave unpublished events behind (their schedulers are disabled)
        }
    }

    @Test
    void reservationEventIsCommittedWithTheReservationAndThenPublished() {
        long productId = api.createProduct(5);
        String reservationId = api.reserveOrFail(productId, "alice", 1);

        OutboxEvent event = outboxRepository.findByAggregateIdOrderById(reservationId).getFirst();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getEventType()).isEqualTo("ReservationCreated");

        assertThat(publisher.publishDueEvents()).isGreaterThanOrEqualTo(1);

        OutboxEvent published = outboxRepository.findById(event.getId()).orElseThrow();
        assertThat(published.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(published.getPublishedAt()).isNotNull();

        ConsumerRecord<String, String> record = readFromTopic(properties.kafka().reservationEventsTopic(),
                event.getEventId());
        assertThat(record.key()).isEqualTo(reservationId);
        assertThat(new String(record.headers().lastHeader("eventType").value())).isEqualTo("ReservationCreated");
        JsonNode envelope = parse(record.value());
        assertThat(envelope.path("payload").path("productId").asLong()).isEqualTo(productId);
        assertThat(envelope.path("payload").path("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void orderConfirmedEventUpdatesSalesProjectionOnce() {
        long productId = api.createProduct(10, "2.50");
        String orderId = api.createOrder("alice", api.reserveOrFail(productId, "alice", 4)).text("orderId");
        api.confirmOrder(orderId);

        publisher.publishDueEvents();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            JsonNode sales = api.get("/api/products/" + productId + "/sales").body();
            assertThat(sales.get("unitsSold").asLong()).isEqualTo(4);
            assertThat(sales.get("revenue").decimalValue()).isEqualByComparingTo("10.00");
        });
    }

    /**
     * Simulates the publisher dying after Kafka acknowledged the send but before PUBLISHED was
     * committed: the row goes back to PENDING and the same event (same eventId) is sent again.
     */
    @Test
    void redeliveredEventIsDetectedAndNotAppliedTwice() {
        long productId = api.createProduct(10, "1.00");
        String orderId = api.createOrder("alice", api.reserveOrFail(productId, "alice", 3)).text("orderId");
        api.confirmOrder(orderId);
        publisher.publishDueEvents();
        await().atMost(Duration.ofSeconds(30)).until(() -> unitsSold(productId) == 3);

        jdbc.sql("""
                        UPDATE outbox_events SET status = 'PENDING', published_at = NULL
                         WHERE aggregate_id = :orderId AND event_type = 'OrderConfirmed'
                        """)
                .param("orderId", orderId).update();
        publisher.publishDueEvents();
        publisher.publishDueEvents();

        // Other cached test contexts are members of the same consumer group, so the duplicate may be
        // handled by any of them. Wait until the group has committed past the end of the topic,
        // i.e. the redelivered record has been processed by someone.
        await().atMost(Duration.ofSeconds(30)).until(this::salesConsumerCaughtUp);
        assertThat(unitsSold(productId)).isEqualTo(3);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM processed_events p JOIN outbox_events o ON o.event_id = p.event_id
                         WHERE o.aggregate_id = :orderId
                        """).param("orderId", orderId).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void concurrentDeliveriesOfTheSameEventApplyOnce() throws Exception {
        long productId = api.createProduct(10, "1.00");
        String orderId = api.createOrder("alice", api.reserveOrFail(productId, "alice", 2)).text("orderId");
        api.confirmOrder(orderId);
        OutboxEvent confirmed = outboxRepository.findByAggregateIdOrderById(orderId).stream()
                .filter(e -> e.getEventType().equals("OrderConfirmed")).findFirst().orElseThrow();
        EventEnvelope envelope = new EventEnvelope(confirmed.getEventId(), confirmed.getEventType(),
                confirmed.getAggregateType(), confirmed.getAggregateId(), confirmed.getCreatedAt(), null,
                objectMapper.readTree(confirmed.getPayload()));

        List<Boolean> applied = Concurrently.run(10, i -> {
            try {
                return salesProjection.applyOrderConfirmed(envelope);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(applied).containsOnlyOnce(true);
        assertThat(unitsSold(productId)).isEqualTo(2);
    }

    private long unitsSold(long productId) {
        return salesProjection.get(productId).unitsSold();
    }

    private boolean salesConsumerCaughtUp() throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                TestContainers.KAFKA.getBootstrapServers()))) {
            Map<TopicPartition, OffsetAndMetadata> committed = admin
                    .listConsumerGroupOffsets("stocklock-" + SalesProjection.CONSUMER)
                    .partitionsToOffsetAndMetadata().get();
            Map<TopicPartition, OffsetSpec> latest = committed.keySet().stream()
                    .filter(tp -> tp.topic().equals(properties.kafka().orderEventsTopic()))
                    .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));
            if (latest.isEmpty()) {
                return false;
            }
            return admin.listOffsets(latest).all().get().entrySet().stream()
                    .allMatch(e -> committed.get(e.getKey()).offset() >= e.getValue().offset());
        }
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ConsumerRecord<String, String> readFromTopic(String topic, UUID eventId) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, TestContainers.KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.value().contains(eventId.toString())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("Event " + eventId + " not found on " + topic);
    }
}
