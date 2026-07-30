package com.tiktok.paymentservice.repository;

import com.tiktok.paymentservice.entity.OrderReference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderReferenceRepository extends JpaRepository<OrderReference, Long> {

    Optional<OrderReference> findByOrderId(Long orderId);
}
