package com.tiktok.cartservice.service;

import com.tiktok.cartservice.cache.CartCacheRepository;
import com.tiktok.cartservice.cache.CartItemData;
import com.tiktok.cartservice.client.ProductClient;
import com.tiktok.cartservice.client.ProductSummary;
import com.tiktok.cartservice.dto.response.CartItemResponse;
import com.tiktok.cartservice.dto.response.CartResponse;
import com.tiktok.cartservice.entity.CartItem;
import com.tiktok.cartservice.exception.CartItemNotFoundException;
import com.tiktok.cartservice.exception.ProductNotFoundException;
import com.tiktok.cartservice.mapper.CartItemMapper;
import com.tiktok.cartservice.repository.CartItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    private final CartItemRepository cartItemRepository;
    private final CartCacheRepository cartCacheRepository;
    private final ProductClient productClient;
    private final CartItemMapper cartItemMapper;

    @Override
    @Transactional(readOnly = true)
    public CartResponse getCart(Long userId) {
        List<CartItemData> items = cartCacheRepository.exists(userId)
                ? cartCacheRepository.getAll(userId)
                : rehydrateFromPostgres(userId);

        return toCartResponse(items);
    }

    @Override
    @Transactional
    public CartResponse addItem(Long userId, Long productId, int quantity) {
        ProductSummary product = productClient.getProduct(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        Optional<CartItem> existing = cartItemRepository.findByUserIdAndProductIdAndDeletedAtIsNull(userId, productId);
        CartItem item;
        if (existing.isPresent()) {
            item = existing.get();
            item.increaseQuantity(quantity);
        } else {
            item = cartItemRepository.save(CartItem.builder()
                    .userId(userId)
                    .productId(productId)
                    .productName(product.name())
                    .price(product.price())
                    .quantity(quantity)
                    .build());
        }

        cartCacheRepository.put(userId, toCacheData(item));

        return getCart(userId);
    }

    @Override
    @Transactional
    public CartResponse updateItemQuantity(Long userId, Long productId, int quantity) {
        CartItem item = cartItemRepository.findByUserIdAndProductIdAndDeletedAtIsNull(userId, productId)
                .orElseThrow(() -> new CartItemNotFoundException(productId));

        item.updateQuantity(quantity);
        cartCacheRepository.put(userId, toCacheData(item));

        return getCart(userId);
    }

    @Override
    @Transactional
    public CartResponse removeItem(Long userId, Long productId) {
        CartItem item = cartItemRepository.findByUserIdAndProductIdAndDeletedAtIsNull(userId, productId)
                .orElseThrow(() -> new CartItemNotFoundException(productId));

        item.markDeleted();
        cartCacheRepository.remove(userId, productId);

        return getCart(userId);
    }

    @Override
    @Transactional
    public void clearCart(Long userId) {
        List<CartItem> items = cartItemRepository.findByUserIdAndDeletedAtIsNull(userId);
        items.forEach(CartItem::markDeleted);
        cartCacheRepository.deleteAll(userId);
    }

    private List<CartItemData> rehydrateFromPostgres(Long userId) {
        List<CartItemData> items = cartItemRepository.findByUserIdAndDeletedAtIsNull(userId).stream()
                .map(this::toCacheData)
                .toList();

        if (!items.isEmpty()) {
            cartCacheRepository.putAll(userId, items);
        }

        return items;
    }

    private CartResponse toCartResponse(List<CartItemData> items) {
        List<CartItemResponse> responses = items.stream()
                .map(cartItemMapper::toResponse)
                .toList();

        BigDecimal total = responses.stream()
                .map(CartItemResponse::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new CartResponse(responses, total);
    }

    private CartItemData toCacheData(CartItem item) {
        return new CartItemData(item.getProductId(), item.getProductName(), item.getPrice(), item.getQuantity());
    }
}
