package com.stocklock.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocklock.common.error.InvalidStateTransitionException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    private static Order order() {
        return Order.create(UUID.randomUUID(), "user-1", List.of(
                new Order.Line(UUID.randomUUID(), 1L, 2, new BigDecimal("10.25")),
                new Order.Line(UUID.randomUUID(), 2L, 1, new BigDecimal("5.00"))), NOW);
    }

    @Test
    void totalIsSumOfUnitPriceTimesQuantity() {
        Order order = order();

        assertThat(order.getTotalAmount()).isEqualByComparingTo("25.50");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.getItems()).hasSize(2);
    }

    @Test
    void createdCanBeConfirmedOnce() {
        Order order = order();
        order.confirm(NOW);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThatThrownBy(() -> order.confirm(NOW)).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> order.cancel(NOW)).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void cancelledCannotBeConfirmed() {
        Order order = order();
        order.cancel(NOW);

        assertThatThrownBy(() -> order.confirm(NOW))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("CANCELLED to CONFIRMED");
    }

    @Test
    void orderNeedsAtLeastOneLine() {
        assertThatThrownBy(() -> Order.create(UUID.randomUUID(), "u", List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
