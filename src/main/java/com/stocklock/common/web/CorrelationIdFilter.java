package com.stocklock.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** Caller-supplied ids end up in logs and Kafka headers, so only accept a safe, bounded format. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = sanitize(request.getHeader(CorrelationId.CORRELATION_HEADER));
        String requestId = UUID.randomUUID().toString();
        MDC.put(CorrelationId.CORRELATION_MDC_KEY, correlationId);
        MDC.put(CorrelationId.REQUEST_MDC_KEY, requestId);
        response.setHeader(CorrelationId.CORRELATION_HEADER, correlationId);
        response.setHeader(CorrelationId.REQUEST_HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationId.CORRELATION_MDC_KEY);
            MDC.remove(CorrelationId.REQUEST_MDC_KEY);
        }
    }

    private static String sanitize(String candidate) {
        return candidate != null && SAFE_ID.matcher(candidate).matches() ? candidate : UUID.randomUUID().toString();
    }
}
