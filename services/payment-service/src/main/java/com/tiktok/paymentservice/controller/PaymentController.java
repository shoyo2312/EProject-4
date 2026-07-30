package com.tiktok.paymentservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.paymentservice.dto.response.PaymentResponse;
import com.tiktok.paymentservice.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @GetMapping("/orders/{orderId}")
    public ApiResponse<PaymentResponse> getByOrderId(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long orderId) {
        return ApiResponse.success(paymentService.getByOrderId(currentUserId, orderId));
    }

    @GetMapping
    public ApiResponse<List<PaymentResponse>> listMine(@AuthenticationPrincipal Long currentUserId) {
        return ApiResponse.success(paymentService.listByUser(currentUserId));
    }
}
