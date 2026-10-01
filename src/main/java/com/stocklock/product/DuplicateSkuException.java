package com.stocklock.product;

import com.stocklock.common.error.DomainException;
import com.stocklock.common.error.ErrorCode;

public class DuplicateSkuException extends DomainException {

    public DuplicateSkuException(String sku) {
        super(ErrorCode.DUPLICATE_SKU, "A product with SKU %s already exists".formatted(sku));
    }
}
