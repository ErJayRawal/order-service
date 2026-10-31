package com.acme.cloud.orders.exception;

import com.acme.cloud.orders.dto.ApiError;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.stream.Collectors;

public final class OrderExceptionMappers {

    private OrderExceptionMappers() {
    }

    @Provider
    public static class NotFoundMapper implements ExceptionMapper<OrderNotFoundException> {
        @Override
        public Response toResponse(OrderNotFoundException exception) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ApiError("ORDER_NOT_FOUND", exception.getMessage()))
                    .build();
        }
    }

    @Provider
    public static class ValidationMapper implements ExceptionMapper<ConstraintViolationException> {
        @Override
        public Response toResponse(ConstraintViolationException exception) {
            String message = exception.getConstraintViolations().stream()
                    .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; "));
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiError("VALIDATION_FAILED", message))
                    .build();
        }
    }
}
