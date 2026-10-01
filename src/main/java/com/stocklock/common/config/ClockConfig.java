package com.stocklock.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * All timestamps (expiry deadlines, outbox retry times, audit columns) come from this clock rather
 * than from {@code now()} in SQL, so that expiration can be tested by moving time instead of sleeping.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
