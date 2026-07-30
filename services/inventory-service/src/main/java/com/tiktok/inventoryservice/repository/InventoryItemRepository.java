package com.tiktok.inventoryservice.repository;

import com.tiktok.inventoryservice.entity.InventoryItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, Long> {

    Optional<InventoryItem> findByProductIdAndDeletedAtIsNull(Long productId);

    boolean existsByProductIdAndDeletedAtIsNull(Long productId);
}
