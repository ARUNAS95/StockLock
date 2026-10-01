package com.stocklock.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import com.stocklock.support.IntegrationTest;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** A failed publish leaves the event PENDING, is retried after backoff, and succeeds once Kafka is back. */
class OutboxRetryIT extends IntegrationTest {

    @MockitoSpyBean
    @SuppressWarnings("rawtypes")
    private KafkaTemplate kafkaTemplate;

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private OutboxRepository outboxRepository;

    @Test
    void failedPublishIsRetriedWithBackoffUntilItSucceeds() {
        while (publisher.publishDueEvents() > 0) {
            // flush backlog left by other test classes so our event is first in line
        }
        long productId = api.createProduct(5);
        String reservationId = api.reserveOrFail(productId, "alice", 1);

        doReturn(CompletableFuture.failedFuture(new TimeoutException("Topic not present in metadata after 5000 ms")))
                .when(kafkaTemplate).send(any(ProducerRecord.class));
        try {
            assertThat(publisher.publishDueEvents()).isZero();
            OutboxEvent failed = event(reservationId);
            assertThat(failed.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(failed.getRetryCount()).isEqualTo(1);
            assertThat(failed.getLastError()).contains("TimeoutException");
            assertThat(failed.getNextAttemptAt()).isAfter(clock.instant());

            // Not due yet: the publisher does not hammer a broker that just failed.
            publisher.publishDueEvents();
            assertThat(event(reservationId).getRetryCount()).isEqualTo(1);
        } finally {
            Mockito.reset(kafkaTemplate);
        }

        clock.advance(Duration.ofSeconds(2));
        assertThat(publisher.publishDueEvents()).isGreaterThanOrEqualTo(1);

        OutboxEvent published = event(reservationId);
        assertThat(published.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(published.getRetryCount()).isEqualTo(1);
        assertThat(published.getLastError()).isNull();
    }

    private OutboxEvent event(String reservationId) {
        return outboxRepository.findByAggregateIdOrderById(reservationId).getFirst();
    }
}
