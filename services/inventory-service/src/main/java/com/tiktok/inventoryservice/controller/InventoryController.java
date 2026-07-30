package com.tiktok.inventoryservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.inventoryservice.dto.request.RestockRequest;
import com.tiktok.inventoryservice.dto.response.InventoryResponse;
import com.tiktok.inventoryservice.service.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @GetMapping("/{productId}")
    public ApiResponse<InventoryResponse> getStock(@PathVariable Long productId) {
        return ApiResponse.success(inventoryService.getStock(productId));
    }

    @PostMapping("/{productId}/restock")
    public ApiResponse<InventoryResponse> restock(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long productId,
            @Valid @RequestBody RestockRequest request) {
        return ApiResponse.success(inventoryService.restock(currentUserId, productId, request.quantity()));
    }
}
