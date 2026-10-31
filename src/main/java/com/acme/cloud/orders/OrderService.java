package com.acme.cloud.orders;

import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class OrderService {

    private final OrderRepository orders;

    @Inject
    public OrderService(OrderRepository orders) {
        this.orders = orders;
    }

    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        Instant now = Instant.now();
        OrderEntity order = new OrderEntity();
        order.id = UUID.randomUUID();
        order.customerId = request.customerId();
        order.currency = request.currency();
        order.status = OrderStatus.PENDING;
        order.createdAt = now;
        order.updatedAt = now;
        order.total = BigDecimal.ZERO;

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

        order.persist();
        return toResponse(order);
    }

    @Transactional
    public OrderResponse get(UUID id) {
        return toResponse(findOrder(id));
    }

    @Transactional
    public List<OrderResponse> list(int page, int size) {
        return orders.find("order by createdAt desc, id desc")
                .page(Page.of(page, size))
                .list()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public OrderResponse cancel(UUID id) {
        OrderEntity order = findOrder(id);
        if (order.status == OrderStatus.PENDING) {
            order.status = OrderStatus.CANCELLED;
            order.updatedAt = Instant.now();
        }
        return toResponse(order);
    }

    private OrderEntity findOrder(UUID id) {
        return orders.findByIdOptional(id).orElseThrow(() -> new OrderNotFoundException(id));
    }

    private OrderResponse toResponse(OrderEntity order) {
        List<OrderItemResponse> items = order.items.stream()
                .map(item -> new OrderItemResponse(item.productId, item.quantity, item.unitPrice, lineTotal(item)))
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
