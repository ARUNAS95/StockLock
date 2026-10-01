package com.stocklock.idempotency;

import com.stocklock.common.error.DomainException;
import com.stocklock.common.error.ErrorCode;

public class IdempotencyKeyReusedException extends DomainException {

    public IdempotencyKeyReusedException(String key) {
        super(ErrorCode.DUPLICATE_IDEMPOTENCY_KEY,
                "Idempotency-Key '%s' was already used for a different request".formatted(key));
    }
}
