package com.acme.cloud.orders.api;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jboss.logmanager.MDC;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class CorrelationIdFilter implements ContainerRequestFilter, ContainerResponseFilter {

    static final String HEADER_NAME = "X-Correlation-ID";
    private static final String REQUEST_PROPERTY = CorrelationIdFilter.class.getName() + ".correlationId";
    private static final Pattern VALID_CORRELATION_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String suppliedId = requestContext.getHeaderString(HEADER_NAME);
        String correlationId = suppliedId != null && VALID_CORRELATION_ID.matcher(suppliedId).matches()
                ? suppliedId
                : UUID.randomUUID().toString();

        requestContext.setProperty(REQUEST_PROPERTY, correlationId);
        MDC.put("correlationId", correlationId);

        SpanContext spanContext = Span.current().getSpanContext();
        if (spanContext.isValid()) {
            MDC.put("traceId", spanContext.getTraceId());
            MDC.put("spanId", spanContext.getSpanId());
        }
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        Object correlationId = requestContext.getProperty(REQUEST_PROPERTY);
        if (correlationId instanceof String id) {
            responseContext.getHeaders().putSingle(HEADER_NAME, id);
        }

        MDC.remove("correlationId");
        MDC.remove("traceId");
        MDC.remove("spanId");
    }
}
