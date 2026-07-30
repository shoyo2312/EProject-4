package com.tiktok.orderservice.repository;

import com.tiktok.orderservice.entity.OrderLineItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderLineItemRepository extends JpaRepository<OrderLineItem, Long> {

    List<OrderLineItem> findByOrderId(Long orderId);
}
