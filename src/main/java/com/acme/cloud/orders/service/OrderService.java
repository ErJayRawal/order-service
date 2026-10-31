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
import org.jboss.logging.Logger;

@ApplicationScoped
public class OrderService {

    private static final Logger LOG = Logger.getLogger(OrderService.class);
    private final OrderRepository orders;
    private final OrderMapper orderMapper;

    @Inject
    public OrderService(OrderRepository orders, OrderMapper orderMapper) {
        this.orders = orders;
        this.orderMapper = orderMapper;
    }

    @Transactional
    public OrderResponse create(CreateOrderRequest request, String customerId) {
        OrderEntity order = orderMapper.toEntity(request, customerId, Instant.now());
        orders.persist(order);
        LOG.infof("Order created id=%s", order.id);
        return orderMapper.toResponse(order);
    }

    @Transactional
    public OrderResponse get(UUID id, String customerId) {
        return orderMapper.toResponse(findOrder(id, customerId));
    }

    @Transactional
    public List<OrderResponse> list(String customerId, int page, int size) {
        return orders.findPageForCustomer(customerId, page, size).stream()
                .map(orderMapper::toResponse)
                .toList();
    }

    @Transactional
    public OrderResponse cancel(UUID id, String customerId) {
        OrderEntity order = findOrder(id, customerId);
        order.cancel(Instant.now());
        LOG.infof("Order cancellation processed id=%s status=%s", order.id, order.status);
        return orderMapper.toResponse(order);
    }

    private OrderEntity findOrder(UUID id, String customerId) {
        return orders.findOwnedById(id, customerId).orElseThrow(() -> new OrderNotFoundException(id));
    }
}
