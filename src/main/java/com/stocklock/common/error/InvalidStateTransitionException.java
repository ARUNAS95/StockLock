package com.stocklock.common.error;

public class InvalidStateTransitionException extends DomainException {

    public InvalidStateTransitionException(String entity, Object id, Enum<?> from, Enum<?> to) {
        super(ErrorCode.INVALID_STATE_TRANSITION,
                "%s %s cannot transition from %s to %s".formatted(entity, id, from, to));
    }

    public InvalidStateTransitionException(String message) {
        super(ErrorCode.INVALID_STATE_TRANSITION, message);
    }
}
