package com.stocklock.inventory;

import com.stocklock.common.error.DomainException;
import com.stocklock.common.error.ErrorCode;

public class OutOfStockException extends DomainException {

    public OutOfStockException(String sku, int requested) {
        super(ErrorCode.OUT_OF_STOCK, "Insufficient inventory for product %s (requested %d)".formatted(sku, requested));
    }
}
