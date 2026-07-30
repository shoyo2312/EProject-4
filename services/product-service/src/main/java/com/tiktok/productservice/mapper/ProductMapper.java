package com.tiktok.productservice.mapper;

import com.tiktok.productservice.dto.response.ProductResponse;
import com.tiktok.productservice.entity.Product;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ProductMapper {

    ProductResponse toResponse(Product product);
}
