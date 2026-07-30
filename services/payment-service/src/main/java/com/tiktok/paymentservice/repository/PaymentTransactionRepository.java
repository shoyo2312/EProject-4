package com.tiktok.paymentservice.repository;

import com.tiktok.paymentservice.entity.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    Optional<PaymentTransaction> findFirstByOrderIdOrderByCreatedAtDesc(Long orderId);

    List<PaymentTransaction> findByUserIdOrderByCreatedAtDesc(Long userId);
}
