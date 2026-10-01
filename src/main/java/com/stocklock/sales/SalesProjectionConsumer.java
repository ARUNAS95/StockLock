package com.stocklock.sales;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocklock.common.metrics.StockLockMetrics;
import com.stocklock.common.web.CorrelationId;
import com.stocklock.events.EventEnvelope;
import com.stocklock.events.EventTypes;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka delivery is treated as at-least-once: the same event can arrive again after a publisher
 * retry, a consumer rebalance, or a crash between processing and offset commit. Each delivery is
 * de-duplicated by eventId inside the projection transaction. The offset is committed only after
 * the listener returns, so a failure before the database commit leads to redelivery, not loss.
 */
@Component
public class SalesProjectionConsumer {

    private static final Logger log = LoggerFactory.getLogger(SalesProjectionConsumer.class);

    private final SalesProjection salesProjection;
    private final ObjectMapper objectMapper;
    private final StockLockMetrics metrics;

    public SalesProjectionConsumer(SalesProjection salesProjection, ObjectMapper objectMapper,
                                   StockLockMetrics metrics) {
        this.salesProjection = salesProjection;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @KafkaListener(id = SalesProjection.CONSUMER, groupId = "stocklock-" + SalesProjection.CONSUMER,
            topics = "${stocklock.kafka.order-events-topic}")
    public void onOrderEvent(ConsumerRecord<String, String> record) throws JsonProcessingException {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        if (envelope.correlationId() != null) {
            MDC.put(CorrelationId.CORRELATION_MDC_KEY, envelope.correlationId());
        }
        try {
            handle(envelope, record);
        } finally {
            MDC.remove(CorrelationId.CORRELATION_MDC_KEY);
        }
    }

    private void handle(EventEnvelope envelope, ConsumerRecord<String, String> record) throws JsonProcessingException {
        if (!EventTypes.ORDER_CONFIRMED.equals(envelope.eventType())) {
            metrics.eventConsumed(SalesProjection.CONSUMER, "ignored");
            return;
        }
        if (salesProjection.applyOrderConfirmed(envelope)) {
            metrics.eventConsumed(SalesProjection.CONSUMER, "processed");
            log.info("Applied {} eventId={} orderId={} partition={} offset={}", envelope.eventType(),
                    envelope.eventId(), envelope.aggregateId(), record.partition(), record.offset());
        } else {
            metrics.eventConsumed(SalesProjection.CONSUMER, "duplicate");
            log.info("Skipped duplicate {} eventId={} partition={} offset={}", envelope.eventType(),
                    envelope.eventId(), record.partition(), record.offset());
        }
    }
}
