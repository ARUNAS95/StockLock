package com.stocklock.support;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;

/**
 * One PostgreSQL, Redis and Kafka per test JVM, shared by every Spring context (including the
 * extra "instances" started by multi-instance tests). Ryuk removes the containers when the JVM exits.
 */
public final class TestContainers {

    public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("stocklock")
            // Several application contexts (= simulated instances) each hold their own pool.
            .withCommand("postgres", "-c", "max_connections=500");

    public static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    public static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.9.1");

    static {
        Startables.deepStart(POSTGRES, REDIS, KAFKA).join();
    }

    private TestContainers() {
    }

    /** Connection properties for an application context, as a fresh mutable map. */
    public static Map<String, Object> connectionProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.datasource.url", POSTGRES.getJdbcUrl());
        properties.put("spring.datasource.username", POSTGRES.getUsername());
        properties.put("spring.datasource.password", POSTGRES.getPassword());
        properties.put("spring.data.redis.host", REDIS.getHost());
        properties.put("spring.data.redis.port", REDIS.getMappedPort(6379));
        properties.put("spring.kafka.bootstrap-servers", KAFKA.getBootstrapServers());
        return properties;
    }

    public static void register(DynamicPropertyRegistry registry) {
        connectionProperties().forEach((key, value) -> registry.add(key, () -> value));
    }

    /** A TCP port on localhost that nothing listens on, to simulate an unreachable dependency. */
    public static int closedPort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
