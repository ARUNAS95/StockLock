package com.stocklock.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false, unique = true)
    private UUID reservationId;

    @Column(nullable = false)
    private int quantity;

    /** Unit price captured when the order was created; later product price changes do not affect it. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    protected OrderItem() {
    }

    OrderItem(Order order, long productId, UUID reservationId, int quantity, BigDecimal price) {
        this.order = order;
        this.productId = productId;
        this.reservationId = reservationId;
        this.quantity = quantity;
        this.price = price;
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getPrice() {
        return price;
    }
}
