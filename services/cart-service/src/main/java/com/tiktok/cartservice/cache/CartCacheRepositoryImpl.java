package com.tiktok.cartservice.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class CartCacheRepositoryImpl implements CartCacheRepository {

    private static final String KEY_PREFIX = "cart:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public boolean exists(Long userId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key(userId)));
    }

    @Override
    @SneakyThrows
    public List<CartItemData> getAll(Long userId) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key(userId));
        List<CartItemData> items = new java.util.ArrayList<>();
        for (Object json : entries.values()) {
            items.add(objectMapper.readValue((String) json, CartItemData.class));
        }
        return items;
    }

    @Override
    @SneakyThrows
    public void put(Long userId, CartItemData item) {
        redisTemplate.opsForHash().put(key(userId), String.valueOf(item.productId()), objectMapper.writeValueAsString(item));
    }

    @Override
    public void remove(Long userId, Long productId) {
        redisTemplate.opsForHash().delete(key(userId), String.valueOf(productId));
    }

    @Override
    public void putAll(Long userId, List<CartItemData> items) {
        items.forEach(item -> put(userId, item));
    }

    @Override
    public void deleteAll(Long userId) {
        redisTemplate.delete(key(userId));
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}
