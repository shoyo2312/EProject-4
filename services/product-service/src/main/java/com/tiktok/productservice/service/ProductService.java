package com.tiktok.productservice.service;

import com.tiktok.productservice.dto.request.CreateProductRequest;
import com.tiktok.productservice.dto.request.UpdateProductRequest;
import com.tiktok.productservice.dto.response.ProductResponse;

import java.util.List;

public interface ProductService {

    ProductResponse create(Long sellerId, CreateProductRequest request);

    ProductResponse update(Long sellerId, Long productId, UpdateProductRequest request);

    ProductResponse getById(Long productId);

    List<ProductResponse> listActive();

    List<ProductResponse> listBySeller(Long sellerId);

    void delete(Long sellerId, Long productId);
}
