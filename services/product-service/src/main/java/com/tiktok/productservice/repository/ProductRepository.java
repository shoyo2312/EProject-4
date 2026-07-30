package com.tiktok.productservice.repository;

import com.tiktok.productservice.entity.Product;
import com.tiktok.productservice.entity.ProductStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByIdAndDeletedAtIsNull(Long id);

    List<Product> findBySellerIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long sellerId);

    List<Product> findByStatusAndDeletedAtIsNullOrderByCreatedAtDesc(ProductStatus status);
}
