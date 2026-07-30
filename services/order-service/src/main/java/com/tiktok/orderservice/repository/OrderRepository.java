package com.tiktok.orderservice.repository;

import com.tiktok.orderservice.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByIdAndDeletedAtIsNull(Long id);

    List<Order> findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long userId);
}
