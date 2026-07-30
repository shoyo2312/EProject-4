package com.tiktok.orderservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.orderservice.dto.request.CancelOrderRequest;
import com.tiktok.orderservice.dto.response.OrderResponse;
import com.tiktok.orderservice.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderResponse> checkout(
            @AuthenticationPrincipal Long currentUserId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return ApiResponse.success(orderService.checkout(currentUserId, authorization));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderResponse> getById(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long orderId) {
        return ApiResponse.success(orderService.getById(currentUserId, orderId));
    }

    @GetMapping
    public ApiResponse<List<OrderResponse>> listMine(@AuthenticationPrincipal Long currentUserId) {
        return ApiResponse.success(orderService.listByUser(currentUserId));
    }

    @PostMapping("/{orderId}/cancel")
    public ApiResponse<OrderResponse> cancel(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long orderId,
            @Valid @RequestBody CancelOrderRequest request) {
        return ApiResponse.success(orderService.cancel(currentUserId, orderId, request.reason()));
    }
}
