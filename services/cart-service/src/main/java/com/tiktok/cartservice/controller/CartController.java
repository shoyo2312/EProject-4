package com.tiktok.cartservice.controller;

import com.tiktok.cartservice.dto.request.AddCartItemRequest;
import com.tiktok.cartservice.dto.request.UpdateCartItemQuantityRequest;
import com.tiktok.cartservice.dto.response.CartResponse;
import com.tiktok.common.response.ApiResponse;
import com.tiktok.cartservice.service.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @GetMapping
    public ApiResponse<CartResponse> getCart(@AuthenticationPrincipal Long currentUserId) {
        return ApiResponse.success(cartService.getCart(currentUserId));
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CartResponse> addItem(
            @AuthenticationPrincipal Long currentUserId,
            @Valid @RequestBody AddCartItemRequest request) {
        return ApiResponse.success(cartService.addItem(currentUserId, request.productId(), request.quantity()));
    }

    @PatchMapping("/items/{productId}")
    public ApiResponse<CartResponse> updateItemQuantity(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long productId,
            @Valid @RequestBody UpdateCartItemQuantityRequest request) {
        return ApiResponse.success(cartService.updateItemQuantity(currentUserId, productId, request.quantity()));
    }

    @DeleteMapping("/items/{productId}")
    public ApiResponse<CartResponse> removeItem(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long productId) {
        return ApiResponse.success(cartService.removeItem(currentUserId, productId));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearCart(@AuthenticationPrincipal Long currentUserId) {
        cartService.clearCart(currentUserId);
    }
}
