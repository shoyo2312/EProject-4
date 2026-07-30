package com.tiktok.productservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.productservice.dto.request.CreateProductRequest;
import com.tiktok.productservice.dto.request.UpdateProductRequest;
import com.tiktok.productservice.dto.response.ProductResponse;
import com.tiktok.productservice.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProductResponse> create(
            @AuthenticationPrincipal Long currentUserId,
            @Valid @RequestBody CreateProductRequest request) {
        return ApiResponse.success(productService.create(currentUserId, request));
    }

    @PatchMapping("/{productId}")
    public ApiResponse<ProductResponse> update(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long productId,
            @Valid @RequestBody UpdateProductRequest request) {
        return ApiResponse.success(productService.update(currentUserId, productId, request));
    }

    @GetMapping("/{productId}")
    public ApiResponse<ProductResponse> getById(@PathVariable Long productId) {
        return ApiResponse.success(productService.getById(productId));
    }

    @GetMapping
    public ApiResponse<List<ProductResponse>> listActive() {
        return ApiResponse.success(productService.listActive());
    }

    @GetMapping("/sellers/{sellerId}")
    public ApiResponse<List<ProductResponse>> listBySeller(@PathVariable Long sellerId) {
        return ApiResponse.success(productService.listBySeller(sellerId));
    }

    @DeleteMapping("/{productId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long productId) {
        productService.delete(currentUserId, productId);
        return ApiResponse.success(null);
    }
}
