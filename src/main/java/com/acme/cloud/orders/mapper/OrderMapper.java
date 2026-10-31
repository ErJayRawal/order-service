package com.acme.cloud.orders.mapper;

import com.acme.cloud.orders.dto.CreateOrderItemRequest;
import com.acme.cloud.orders.dto.CreateOrderRequest;
import com.acme.cloud.orders.dto.OrderItemResponse;
import com.acme.cloud.orders.dto.OrderResponse;
import com.acme.cloud.orders.entity.OrderEntity;
import com.acme.cloud.orders.entity.OrderItemEntity;
import com.acme.cloud.orders.entity.OrderStatus;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class OrderMapper {

    public OrderEntity toEntity(CreateOrderRequest request, String customerId, Instant createdAt) {
        OrderEntity order = new OrderEntity();
        order.id = UUID.randomUUID();
        order.customerId = customerId;
        order.currency = request.currency();
        order.status = OrderStatus.PENDING;
        order.total = BigDecimal.ZERO;
        order.createdAt = createdAt;
        order.updatedAt = createdAt;

        for (int lineNumber = 0; lineNumber < request.items().size(); lineNumber++) {
            CreateOrderItemRequest itemRequest = request.items().get(lineNumber);
            OrderItemEntity item = new OrderItemEntity();
            item.id = UUID.randomUUID();
            item.order = order;
            item.productId = itemRequest.productId();
            item.quantity = itemRequest.quantity();
            item.lineNumber = lineNumber;
            item.unitPrice = itemRequest.unitPrice();
            order.items.add(item);
            order.total = order.total.add(lineTotal(item));
        }

        return order;
    }

    public OrderResponse toResponse(OrderEntity order) {
        List<OrderItemResponse> items = order.items.stream()
                .map(item -> new OrderItemResponse(
                        item.productId, item.quantity, item.unitPrice, lineTotal(item)))
                .toList();
        return new OrderResponse(
                order.id,
                order.customerId,
                order.currency,
                items,
                order.total,
                order.status,
                order.createdAt,
                order.updatedAt);
    }

    private BigDecimal lineTotal(OrderItemEntity item) {
        return item.unitPrice.multiply(BigDecimal.valueOf(item.quantity));
    }
}
