package com.tiktok.productservice.service;

import com.tiktok.event.product.ProductCreatedEvent;
import com.tiktok.productservice.dto.request.CreateProductRequest;
import com.tiktok.productservice.dto.request.UpdateProductRequest;
import com.tiktok.productservice.dto.response.ProductResponse;
import com.tiktok.productservice.entity.Product;
import com.tiktok.productservice.entity.ProductStatus;
import com.tiktok.productservice.event.producer.ProductEventProducer;
import com.tiktok.productservice.exception.NotProductOwnerException;
import com.tiktok.productservice.exception.ProductNotFoundException;
import com.tiktok.productservice.mapper.ProductMapper;
import com.tiktok.productservice.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final ProductMapper productMapper;
    private final ProductEventProducer productEventProducer;

    @Override
    @Transactional
    public ProductResponse create(Long sellerId, CreateProductRequest request) {
        Product product = Product.builder()
                .sellerId(sellerId)
                .name(request.name())
                .description(request.description())
                .price(request.price())
                .category(request.category())
                .imageUrl(request.imageUrl())
                .status(ProductStatus.ACTIVE)
                .build();

        product = productRepository.save(product);

        productEventProducer.publishProductCreated(
                ProductCreatedEvent.of(product.getId(), sellerId, product.getName(), product.getDescription(),
                        product.getPrice(), product.getCategory(), product.getImageUrl()));

        return productMapper.toResponse(product);
    }

    @Override
    @Transactional
    public ProductResponse update(Long sellerId, Long productId, UpdateProductRequest request) {
        Product product = findVisible(productId);

        if (!sellerId.equals(product.getSellerId())) {
            throw new NotProductOwnerException(productId);
        }

        product.updateDetails(request.name(), request.description(), request.price(), request.category(), request.imageUrl());

        return productMapper.toResponse(product);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductResponse getById(Long productId) {
        return productMapper.toResponse(findVisible(productId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductResponse> listActive() {
        return productRepository.findByStatusAndDeletedAtIsNullOrderByCreatedAtDesc(ProductStatus.ACTIVE).stream()
                .map(productMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductResponse> listBySeller(Long sellerId) {
        return productRepository.findBySellerIdAndDeletedAtIsNullOrderByCreatedAtDesc(sellerId).stream()
                .map(productMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public void delete(Long sellerId, Long productId) {
        Product product = findVisible(productId);

        if (!sellerId.equals(product.getSellerId())) {
            throw new NotProductOwnerException(productId);
        }

        product.markDeleted();
    }

    private Product findVisible(Long productId) {
        return productRepository.findByIdAndDeletedAtIsNull(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
    }
}
