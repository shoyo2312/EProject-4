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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CartServiceImplTest {

    @Mock
    private CartItemRepository cartItemRepository;

    @Mock
    private CartCacheRepository cartCacheRepository;

    @Mock
    private ProductClient productClient;

    @Mock
    private CartItemMapper cartItemMapper;

    private CartServiceImpl cartService;

    @BeforeEach
    void setUp() {
        cartService = new CartServiceImpl(cartItemRepository, cartCacheRepository, productClient, cartItemMapper);
    }

    private CartItemResponse toResponse(CartItemData data) {
        return new CartItemResponse(data.productId(), data.productName(), data.price(), data.quantity(),
                data.price().multiply(BigDecimal.valueOf(data.quantity())));
    }

    private void stubMapperPassthrough() {
        when(cartItemMapper.toResponse(any(CartItemData.class))).thenAnswer(invocation -> toResponse(invocation.getArgument(0)));
    }

    @Test
    void getCart_cacheHit_returnsFromCacheWithoutTouchingPostgres() {
        stubMapperPassthrough();
        CartItemData item = new CartItemData(1L, "Phone case", new BigDecimal("9.99"), 2);
        when(cartCacheRepository.exists(100L)).thenReturn(true);
        when(cartCacheRepository.getAll(100L)).thenReturn(List.of(item));

        CartResponse response = cartService.getCart(100L);

        assertThat(response.items()).hasSize(1);
        assertThat(response.totalAmount()).isEqualByComparingTo("19.98");
        verifyNoInteractions(cartItemRepository);
    }

    @Test
    void getCart_cacheMiss_rehydratesFromPostgresAndPopulatesCache() {
        stubMapperPassthrough();
        CartItem item = CartItem.builder().userId(100L).productId(1L).productName("Phone case").price(new BigDecimal("9.99")).quantity(2).build();
        when(cartCacheRepository.exists(100L)).thenReturn(false);
        when(cartItemRepository.findByUserIdAndDeletedAtIsNull(100L)).thenReturn(List.of(item));

        CartResponse response = cartService.getCart(100L);

        assertThat(response.items()).hasSize(1);
        assertThat(response.totalAmount()).isEqualByComparingTo("19.98");
        verify(cartCacheRepository).putAll(eq(100L), anyList());
    }

    @Test
    void getCart_cacheMissAndEmptyPostgres_returnsEmptyCartWithoutPopulatingCache() {
        when(cartCacheRepository.exists(100L)).thenReturn(false);
        when(cartItemRepository.findByUserIdAndDeletedAtIsNull(100L)).thenReturn(List.of());

        CartResponse response = cartService.getCart(100L);

        assertThat(response.items()).isEmpty();
        assertThat(response.totalAmount()).isEqualByComparingTo("0");
        verify(cartCacheRepository, never()).putAll(any(), any());
    }

    @Test
    void addItem_newProduct_createsCartItemAndCachesIt() {
        stubMapperPassthrough();
        when(productClient.getProduct(1L)).thenReturn(Optional.of(new ProductSummary(1L, "Phone case", new BigDecimal("9.99"))));
        when(cartItemRepository.findByUserIdAndProductIdAndDeletedAtIsNull(100L, 1L)).thenReturn(Optional.empty());
        when(cartItemRepository.save(any(CartItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cartCacheRepository.exists(100L)).thenReturn(true);
        when(cartCacheRepository.getAll(100L)).thenReturn(List.of(new CartItemData(1L, "Phone case", new BigDecimal("9.99"), 2)));

        cartService.addItem(100L, 1L, 2);

        ArgumentCaptor<CartItem> captor = ArgumentCaptor.forClass(CartItem.class);
        verify(cartItemRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(100L);
        assertThat(captor.getValue().getProductId()).isEqualTo(1L);
        assertThat(captor.getValue().getProductName()).isEqualTo("Phone case");
        assertThat(captor.getValue().getQuantity()).isEqualTo(2);

        verify(cartCacheRepository).put(eq(100L), any(CartItemData.class));
    }

    @Test
    void addItem_existingProduct_incrementsQuantityWithoutCreatingNewRow() {
        stubMapperPassthrough();
        CartItem existing = CartItem.builder().userId(100L).productId(1L).productName("Phone case").price(new BigDecimal("9.99")).quantity(2).build();
        when(productClient.getProduct(1L)).thenReturn(Optional.of(new ProductSummary(1L, "Phone case", new BigDecimal("9.99"))));
        when(cartItemRepository.findByUserIdAndProductIdAndDeletedAtIsNull(100L, 1L)).thenReturn(Optional.of(existing));
        when(cartCacheRepository.exists(100L)).thenReturn(true);
        when(cartCacheRepository.getAll(100L)).thenReturn(List.of(new CartItemData(1L, "Phone case", new BigDecimal("9.99"), 5)));

        cartService.addItem(100L, 1L, 3);

        assertThat(existing.getQuantity()).isEqualTo(5);
        verify(cartItemRepository, never()).save(any());
    }

    @Test
    void addItem_productNotFound_throwsProductNotFoundException() {
        when(productClient.getProduct(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cartService.addItem(100L, 1L, 1))
                .isInstanceOf(ProductNotFoundException.class);
        verifyNoInteractions(cartItemRepository);
    }

    @Test
    void updateItemQuantity_existingItem_updatesQuantityAndCache() {
        stubMapperPassthrough();
        CartItem existing = CartItem.builder().userId(100L).productId(1L).productName("Phone case").price(new BigDecimal("9.99")).quantity(2).build();
        when(cartItemRepository.findByUserIdAndProductIdAndDeletedAtIsNull(100L, 1L)).thenReturn(Optional.of(existing));
        when(cartCacheRepository.exists(100L)).thenReturn(true);
        when(cartCacheRepository.getAll(100L)).thenReturn(List.of(new CartItemData(1L, "Phone case", new BigDecimal("9.99"), 7)));

        cartService.updateItemQuantity(100L, 1L, 7);

        assertThat(existing.getQuantity()).isEqualTo(7);
        verify(cartCacheRepository).put(eq(100L), any(CartItemData.class));
    }

    @Test
    void updateItemQuantity_missingItem_throwsCartItemNotFoundException() {
        when(cartItemRepository.findByUserIdAndProductIdAndDeletedAtIsNull(100L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cartService.updateItemQuantity(100L, 1L, 3))
                .isInstanceOf(CartItemNotFoundException.class);
    }

    @Test
    void removeItem_existingItem_marksDeletedAndRemovesFromCache() {
        CartItem existing = CartItem.builder().userId(100L).productId(1L).productName("Phone case").price(new BigDecimal("9.99")).quantity(2).build();
        when(cartItemRepository.findByUserIdAndProductIdAndDeletedAtIsNull(100L, 1L)).thenReturn(Optional.of(existing));
        when(cartCacheRepository.exists(100L)).thenReturn(false);
        when(cartItemRepository.findByUserIdAndDeletedAtIsNull(100L)).thenReturn(List.of());

        cartService.removeItem(100L, 1L);

        assertThat(existing.isDeleted()).isTrue();
        verify(cartCacheRepository).remove(100L, 1L);
    }

    @Test
    void removeItem_missingItem_throwsCartItemNotFoundException() {
        when(cartItemRepository.findByUserIdAndProductIdAndDeletedAtIsNull(100L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cartService.removeItem(100L, 1L))
                .isInstanceOf(CartItemNotFoundException.class);
    }

    @Test
    void clearCart_marksAllItemsDeletedAndDeletesCacheKey() {
        CartItem item1 = CartItem.builder().userId(100L).productId(1L).productName("A").price(BigDecimal.ONE).quantity(1).build();
        CartItem item2 = CartItem.builder().userId(100L).productId(2L).productName("B").price(BigDecimal.ONE).quantity(1).build();
        when(cartItemRepository.findByUserIdAndDeletedAtIsNull(100L)).thenReturn(List.of(item1, item2));

        cartService.clearCart(100L);

        assertThat(item1.isDeleted()).isTrue();
        assertThat(item2.isDeleted()).isTrue();
        verify(cartCacheRepository).deleteAll(100L);
    }
}
