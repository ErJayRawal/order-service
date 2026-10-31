package com.acme.cloud.orders.service;

import com.acme.cloud.orders.dto.CreateOrderRequest;
import com.acme.cloud.orders.dto.OrderResponse;
import com.acme.cloud.orders.entity.OrderEntity;
import com.acme.cloud.orders.exception.OrderNotFoundException;
import com.acme.cloud.orders.mapper.OrderMapper;
import com.acme.cloud.orders.repository.OrderRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class OrderService {

    private final OrderRepository orders;
    private final OrderMapper orderMapper;

    @Inject
    public OrderService(OrderRepository orders, OrderMapper orderMapper) {
        this.orders = orders;
        this.orderMapper = orderMapper;
    }

    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        OrderEntity order = orderMapper.toEntity(request, Instant.now());
        orders.persist(order);
        return orderMapper.toResponse(order);
    }

    @Transactional
    public OrderResponse get(UUID id) {
        return orderMapper.toResponse(findOrder(id));
    }

    @Transactional
    public List<OrderResponse> list(int page, int size) {
        return orders.findPage(page, size).stream()
                .map(orderMapper::toResponse)
                .toList();
    }

    @Transactional
    public OrderResponse cancel(UUID id) {
        OrderEntity order = findOrder(id);
        order.cancel(Instant.now());
        return orderMapper.toResponse(order);
    }

    private OrderEntity findOrder(UUID id) {
        return orders.findByIdOptional(id).orElseThrow(() -> new OrderNotFoundException(id));
    }
}
