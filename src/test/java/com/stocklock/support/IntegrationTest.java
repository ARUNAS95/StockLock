package com.stocklock.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Full application on a random port against real PostgreSQL, Redis and Kafka containers.
 * Background jobs are not scheduled (profile "it"); tests call them explicitly so that outcomes do
 * not depend on timer races.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@AutoConfigureObservability
@Import(TestClockConfig.class)
public abstract class IntegrationTest {

    @LocalServerPort
    protected int port;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected JdbcClient jdbc;

    protected StockLockClient api;

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        TestContainers.register(registry);
    }

    @BeforeEach
    void setUpIntegrationTest() {
        clock.reset();
        api = new StockLockClient(port);
    }

    protected StockSnapshot stock(long productId) {
        return StockSnapshot.of(jdbc, productId);
    }
}
