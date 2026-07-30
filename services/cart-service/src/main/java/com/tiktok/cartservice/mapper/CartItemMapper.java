package com.tiktok.cartservice.mapper;

import com.tiktok.cartservice.cache.CartItemData;
import com.tiktok.cartservice.dto.response.CartItemResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface CartItemMapper {

    @Mapping(target = "subtotal", expression = "java(data.price().multiply(java.math.BigDecimal.valueOf(data.quantity())))")
    CartItemResponse toResponse(CartItemData data);
}
