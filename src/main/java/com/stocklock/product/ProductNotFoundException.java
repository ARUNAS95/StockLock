package com.stocklock.product;

import com.stocklock.common.error.DomainException;
import com.stocklock.common.error.ErrorCode;

public class ProductNotFoundException extends DomainException {

    public ProductNotFoundException(long productId) {
        super(ErrorCode.PRODUCT_NOT_FOUND, "Product %d not found".formatted(productId));
    }
}
