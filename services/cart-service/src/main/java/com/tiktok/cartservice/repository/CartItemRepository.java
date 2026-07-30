package com.tiktok.cartservice.repository;

import com.tiktok.cartservice.entity.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    List<CartItem> findByUserIdAndDeletedAtIsNull(Long userId);

    Optional<CartItem> findByUserIdAndProductIdAndDeletedAtIsNull(Long userId, Long productId);
}
