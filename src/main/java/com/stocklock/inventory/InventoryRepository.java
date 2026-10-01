package com.stocklock.inventory;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every stock mutation is one conditional UPDATE. PostgreSQL takes a row lock on the inventory row,
 * re-evaluates the WHERE clause against the latest committed version of the row, and only then
 * applies the change. The check ("is there enough?") and the write therefore cannot be separated by
 * another transaction, no matter which process or host issued it. The caller inspects the returned
 * row count: 1 = applied, 0 = the guard rejected it (or the product does not exist).
 */
public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    Optional<Inventory> findByProductId(long productId);

    @Modifying
    @Query(value = """
            UPDATE inventory
               SET available_quantity = available_quantity - :quantity,
                   reserved_quantity  = reserved_quantity + :quantity,
                   version            = version + 1,
                   updated_at         = :now
             WHERE product_id = :productId
               AND available_quantity >= :quantity
            """, nativeQuery = true)
    int reserve(@Param("productId") long productId, @Param("quantity") int quantity, @Param("now") Instant now);

    /** Returns held stock to the shelf (cancellation / expiration). */
    @Modifying
    @Query(value = """
            UPDATE inventory
               SET available_quantity = available_quantity + :quantity,
                   reserved_quantity  = reserved_quantity - :quantity,
                   version            = version + 1,
                   updated_at         = :now
             WHERE product_id = :productId
               AND reserved_quantity >= :quantity
            """, nativeQuery = true)
    int release(@Param("productId") long productId, @Param("quantity") int quantity, @Param("now") Instant now);

    /** Held stock leaves the system because the order was confirmed (sold). */
    @Modifying
    @Query(value = """
            UPDATE inventory
               SET reserved_quantity = reserved_quantity - :quantity,
                   version           = version + 1,
                   updated_at        = :now
             WHERE product_id = :productId
               AND reserved_quantity >= :quantity
            """, nativeQuery = true)
    int consumeReserved(@Param("productId") long productId, @Param("quantity") int quantity, @Param("now") Instant now);

    @Modifying
    @Query(value = """
            UPDATE inventory
               SET available_quantity = available_quantity + :quantity,
                   version            = version + 1,
                   updated_at         = :now
             WHERE product_id = :productId
            """, nativeQuery = true)
    int restock(@Param("productId") long productId, @Param("quantity") int quantity, @Param("now") Instant now);
}
