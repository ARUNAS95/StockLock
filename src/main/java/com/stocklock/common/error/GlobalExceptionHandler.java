package com.stocklock.common.error;

import com.stocklock.common.web.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ApiError> handleDomain(DomainException ex, HttpServletRequest request) {
        log.info("Request rejected: code={} message={}", ex.code(), ex.getMessage());
        return build(ex.code().status(), ex.code().name(), ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleBodyValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiError.FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        return validationFailed("Request body is invalid", request, violations);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> handleParameterValidation(HandlerMethodValidationException ex, HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new ApiError.FieldViolation(
                                result.getMethodParameter().getParameterName(), error.getDefaultMessage())))
                .toList();
        return validationFailed("Request parameters are invalid", request, violations);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(v -> new ApiError.FieldViolation(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return validationFailed("Request is invalid", request, violations);
    }

    @ExceptionHandler({MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    ResponseEntity<ApiError> handleBadRequest(Exception ex, HttpServletRequest request) {
        String message = ex instanceof HttpMessageNotReadableException
                ? "Request body is missing or malformed"
                : ex.getMessage();
        return validationFailed(message, request, List.of());
    }

    /**
     * Lock-wait timeouts and deadlock victims. Postgres has already rolled the transaction back, so
     * nothing was changed and the client may retry (with the same Idempotency-Key where relevant).
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    ResponseEntity<ApiError> handleConcurrencyFailure(ConcurrencyFailureException ex, HttpServletRequest request) {
        log.warn("Concurrency failure, transaction rolled back: {}", ex.getMostSpecificCause().getMessage());
        return build(ErrorCode.CONCURRENCY_CONFLICT.status(), ErrorCode.CONCURRENCY_CONFLICT.name(),
                "The request conflicted with a concurrent update and had no effect; retry it", request, List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        if (ex instanceof ErrorResponse errorResponse) {
            // Framework errors (404 no handler, 405 method not allowed, ...) keep their status.
            HttpStatusCode status = errorResponse.getStatusCode();
            String code = status instanceof HttpStatus known ? known.name() : String.valueOf(status.value());
            return build(status, code, errorResponse.getBody().getDetail(), request, List.of());
        }
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(ErrorCode.INTERNAL_ERROR.status(), ErrorCode.INTERNAL_ERROR.name(),
                "Unexpected error", request, List.of());
    }

    private ResponseEntity<ApiError> validationFailed(
            String message, HttpServletRequest request, List<ApiError.FieldViolation> violations) {
        return build(ErrorCode.VALIDATION_FAILED.status(), ErrorCode.VALIDATION_FAILED.name(), message, request,
                violations);
    }

    private ResponseEntity<ApiError> build(HttpStatusCode status, String code, String message,
                                           HttpServletRequest request, List<ApiError.FieldViolation> violations) {
        ApiError body = new ApiError(Instant.now(clock), status.value(), code, message, request.getRequestURI(),
                CorrelationId.current(), violations);
        return ResponseEntity.status(status).body(body);
    }
}
