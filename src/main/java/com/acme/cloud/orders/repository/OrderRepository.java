package com.acme.cloud.orders.repository;

import com.acme.cloud.orders.entity.OrderEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class OrderRepository implements PanacheRepositoryBase<OrderEntity, UUID> {

    public List<OrderEntity> findPage(int page, int size) {
        return find("order by createdAt desc, id desc")
                .page(Page.of(page, size))
                .list();
    }
}
