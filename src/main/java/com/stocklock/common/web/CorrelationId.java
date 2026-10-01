package com.stocklock.common.web;

import org.slf4j.MDC;

/**
 * Correlation id = the id of a whole business interaction; it is accepted from the caller, echoed
 * back, written to every log line via MDC and copied into outbox events so a Kafka consumer's logs
 * can be joined with the HTTP request that caused the event. Request id = this one HTTP hop.
 */
public final class CorrelationId {

    public static final String CORRELATION_HEADER = "X-Correlation-Id";
    public static final String REQUEST_HEADER = "X-Request-Id";
    public static final String CORRELATION_MDC_KEY = "correlationId";
    public static final String REQUEST_MDC_KEY = "requestId";

    private CorrelationId() {
    }

    public static String current() {
        return MDC.get(CORRELATION_MDC_KEY);
    }
}
