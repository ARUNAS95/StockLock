package com.stocklock.common.error;

import org.springframework.http.HttpStatus;

/** Stable, machine-readable error codes returned in {@link ApiError#code()}. */
public enum ErrorCode {
    OUT_OF_STOCK(HttpStatus.CONFLICT),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND),
    DUPLICATE_SKU(HttpStatus.CONFLICT),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND),
    RESERVATION_EXPIRED(HttpStatus.CONFLICT),
    RESERVATION_ALREADY_ORDERED(HttpStatus.CONFLICT),
    RESERVATION_OWNER_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT),
    DUPLICATE_IDEMPOTENCY_KEY(HttpStatus.UNPROCESSABLE_ENTITY),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    /** Lock timeout or deadlock victim; the operation had no effect and is safe to retry. */
    CONCURRENCY_CONFLICT(HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
