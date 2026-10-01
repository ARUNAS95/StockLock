package com.stocklock.order;

import com.stocklock.common.error.DomainException;
import com.stocklock.common.error.ErrorCode;
import java.util.UUID;

public class OrderNotFoundException extends DomainException {

    public OrderNotFoundException(UUID orderId) {
        super(ErrorCode.ORDER_NOT_FOUND, "Order %s not found".formatted(orderId));
    }
}
