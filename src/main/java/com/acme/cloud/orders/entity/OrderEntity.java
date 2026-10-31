package com.acme.cloud.orders.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id
    public UUID id;

    @Column(name = "customer_id", nullable = false, length = 128)
    public String customerId;

    @Column(nullable = false, length = 3)
    public String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    public OrderStatus status;

    @Column(nullable = false, precision = 18, scale = 2)
    public BigDecimal total;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @Version
    @Column(nullable = false)
    public long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNumber")
    public List<OrderItemEntity> items = new ArrayList<>();

    public void cancel(Instant cancelledAt) {
        if (status == OrderStatus.PENDING) {
            status = OrderStatus.CANCELLED;
            updatedAt = cancelledAt;
        }
    }
}
