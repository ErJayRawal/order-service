package com.acme.cloud.orders;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

@ApplicationScoped
public class OrderRepository implements PanacheRepositoryBase<OrderEntity, UUID> {
}
