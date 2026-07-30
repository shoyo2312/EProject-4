package com.tiktok.orderservice.mapper;

import com.tiktok.orderservice.dto.response.OrderItemResponse;
import com.tiktok.orderservice.entity.OrderLineItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface OrderItemMapper {

    @Mapping(target = "subtotal", expression = "java(item.getPrice().multiply(java.math.BigDecimal.valueOf(item.getQuantity())))")
    OrderItemResponse toResponse(OrderLineItem item);
}
