package com.acme.cloud.orders.repository;

import com.acme.cloud.orders.entity.OrderEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class OrderRepository implements PanacheRepositoryBase<OrderEntity, UUID> {

    public Optional<OrderEntity> findOwnedById(UUID id, String customerId) {
        return find("id = ?1 and customerId = ?2", id, customerId).firstResultOptional();
    }

    public List<OrderEntity> findPageForCustomer(String customerId, int page, int size) {
        return find("customerId = ?1 order by createdAt desc, id desc", customerId)
                .page(Page.of(page, size))
                .list();
    }
}
