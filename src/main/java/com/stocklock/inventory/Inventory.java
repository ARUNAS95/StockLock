package com.stocklock.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * Stock counters for one product. The hot paths never load-modify-save this entity; they use the
 * single-statement conditional updates in {@link InventoryRepository}. {@code @Version} is kept so
 * that any future load-modify-save code path is protected by optimistic locking instead of silently
 * overwriting a concurrent atomic update (every atomic statement also increments {@code version}).
 */
@Entity
@Table(name = "inventory")
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long productId;

    @Column(nullable = false)
    private int availableQuantity;

    @Column(nullable = false)
    private int reservedQuantity;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Inventory() {
    }

    public static Inventory initial(long productId, int availableQuantity, Instant now) {
        if (availableQuantity < 0) {
            throw new IllegalArgumentException("availableQuantity must be >= 0");
        }
        Inventory inventory = new Inventory();
        inventory.productId = productId;
        inventory.availableQuantity = availableQuantity;
        inventory.reservedQuantity = 0;
        inventory.updatedAt = now;
        return inventory;
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public int getAvailableQuantity() {
        return availableQuantity;
    }

    public int getReservedQuantity() {
        return reservedQuantity;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
