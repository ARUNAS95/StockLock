package com.stocklock.sales;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocklock.events.EventEnvelope;
import com.stocklock.events.OrderEventPayload;
import com.stocklock.events.ProcessedEvents;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-product sales totals derived from OrderConfirmed events. The counters are additive, so a
 * duplicate delivery that slipped through would visibly double-count; {@link ProcessedEvents}
 * prevents that.
 */
@Service
public class SalesProjection {

    public static final String CONSUMER = "sales-projection";

    private final ProcessedEvents processedEvents;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SalesProjection(ProcessedEvents processedEvents, JdbcClient jdbcClient, ObjectMapper objectMapper,
                           Clock clock) {
        this.processedEvents = processedEvents;
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** @return false if the event had already been applied and was skipped */
    @Transactional
    public boolean applyOrderConfirmed(EventEnvelope envelope) throws JsonProcessingException {
        if (!processedEvents.markProcessed(envelope.eventId(), CONSUMER)) {
            return false;
        }
        OrderEventPayload order = objectMapper.treeToValue(envelope.payload(), OrderEventPayload.class);
        Timestamp now = Timestamp.from(Instant.now(clock));
        for (OrderEventPayload.Item item : order.items()) {
            jdbcClient.sql("""
                            INSERT INTO product_sales (product_id, units_sold, revenue, updated_at)
                            VALUES (:productId, :units, :revenue, :now)
                            ON CONFLICT (product_id) DO UPDATE
                               SET units_sold = product_sales.units_sold + EXCLUDED.units_sold,
                                   revenue    = product_sales.revenue + EXCLUDED.revenue,
                                   updated_at = EXCLUDED.updated_at
                            """)
                    .param("productId", item.productId())
                    .param("units", item.quantity())
                    .param("revenue", item.price().multiply(BigDecimal.valueOf(item.quantity())))
                    .param("now", now)
                    .update();
        }
        return true;
    }

    @Transactional(readOnly = true)
    public ProductSales get(long productId) {
        Optional<ProductSales> sales = jdbcClient.sql(
                        "SELECT product_id, units_sold, revenue FROM product_sales WHERE product_id = :productId")
                .param("productId", productId)
                .query((rs, row) -> new ProductSales(rs.getLong(1), rs.getLong(2), rs.getBigDecimal(3)))
                .optional();
        return sales.orElse(new ProductSales(productId, 0, BigDecimal.ZERO));
    }

    public record ProductSales(long productId, long unitsSold, BigDecimal revenue) {
    }
}
