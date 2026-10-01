package com.stocklock.common.kafka;

import com.stocklock.common.config.StockLockProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaConfig {

    @Bean
    NewTopic reservationEventsTopic(StockLockProperties properties) {
        return TopicBuilder.name(properties.kafka().reservationEventsTopic())
                .partitions(properties.kafka().partitions())
                .build();
    }

    @Bean
    NewTopic orderEventsTopic(StockLockProperties properties) {
        return TopicBuilder.name(properties.kafka().orderEventsTopic())
                .partitions(properties.kafka().partitions())
                .build();
    }

    /**
     * A listener failure (e.g. the database is briefly unavailable) is retried in-process with
     * backoff; the offset is not committed meanwhile. After the retries are exhausted the record is
     * parked on {@code <topic>-dlt} instead of blocking the partition forever or being dropped.
     */
    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> kafkaOperations) {
        ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
        backOff.setMaxElapsedTime(10_000);
        return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(kafkaOperations), backOff);
    }
}
