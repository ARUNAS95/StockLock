package com.stocklock.common.error;

/**
 * Base class for expected business failures. Being a {@link RuntimeException} means a domain
 * failure inside a {@code @Transactional} method rolls the whole transaction back.
 */
public abstract class DomainException extends RuntimeException {

    private final ErrorCode code;

    protected DomainException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
