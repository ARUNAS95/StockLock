package com.stocklock.common.scheduling;

import com.stocklock.common.config.StockLockProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/** Disabled in most integration tests, which invoke {@link BackgroundJobs} directly for determinism. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "stocklock.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig implements SchedulingConfigurer {

    private final BackgroundJobs jobs;
    private final StockLockProperties properties;

    public SchedulingConfig(BackgroundJobs jobs, StockLockProperties properties) {
        this.jobs = jobs;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(jobs::expireReservations, properties.reservation().expirationInterval());
        registrar.addFixedDelayTask(jobs::publishOutbox, properties.outbox().pollInterval());
        registrar.addFixedDelayTask(jobs::cleanupIdempotencyRecords, properties.idempotency().cleanupInterval());
    }
}
