package com.stocklock.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        String correlationId,
        List<FieldViolation> violations) {

    public record FieldViolation(String field, String message) {
    }
}
