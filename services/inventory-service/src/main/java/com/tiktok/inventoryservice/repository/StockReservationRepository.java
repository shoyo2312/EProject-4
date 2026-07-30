package com.tiktok.inventoryservice.repository;

import com.tiktok.inventoryservice.entity.ReservationStatus;
import com.tiktok.inventoryservice.entity.StockReservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockReservationRepository extends JpaRepository<StockReservation, Long> {

    List<StockReservation> findByOrderIdAndStatus(Long orderId, ReservationStatus status);
}
