package com.acme.cloud.orders.dto;

import com.acme.cloud.orders.entity.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        String customerId,
        String currency,
        List<OrderItemResponse> items,
        BigDecimal total,
        OrderStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
