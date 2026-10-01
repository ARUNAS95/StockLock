package com.stocklock.order;

import com.stocklock.common.error.InvalidStateTransitionException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {

    private static final String ENTITY = "Order";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private UUID orderId;

    @Column(nullable = false, length = 64)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderStatus status;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    @OrderBy("id")
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
    }

    public static Order create(UUID orderId, String userId, List<Line> lines, Instant now) {
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("An order needs at least one line");
        }
        Order order = new Order();
        order.orderId = orderId;
        order.userId = userId;
        order.status = OrderStatus.CREATED;
        order.createdAt = now;
        order.updatedAt = now;
        order.totalAmount = BigDecimal.ZERO;
        for (Line line : lines) {
            order.items.add(new OrderItem(order, line.productId(), line.reservationId(), line.quantity(),
                    line.unitPrice()));
            order.totalAmount = order.totalAmount.add(line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())));
        }
        return order;
    }

    public void confirm(Instant now) {
        transitionTo(OrderStatus.CONFIRMED, now);
    }

    public void cancel(Instant now) {
        transitionTo(OrderStatus.CANCELLED, now);
    }

    public List<UUID> reservationIds() {
        return items.stream().map(OrderItem::getReservationId).toList();
    }

    private void transitionTo(OrderStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException(ENTITY, orderId, status, target);
        }
        status = target;
        updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public String getUserId() {
        return userId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    /** One order line: a reservation turned into a purchase at the product's current price. */
    public record Line(UUID reservationId, long productId, int quantity, BigDecimal unitPrice) {
    }
}
