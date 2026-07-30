package com.tiktok.cartservice.cache;

import java.util.List;

public interface CartCacheRepository {

    boolean exists(Long userId);

    List<CartItemData> getAll(Long userId);

    void put(Long userId, CartItemData item);

    void remove(Long userId, Long productId);

    void putAll(Long userId, List<CartItemData> items);

    void deleteAll(Long userId);
}
