package com.tiktok.inventoryservice.mapper;

import com.tiktok.inventoryservice.dto.response.InventoryResponse;
import com.tiktok.inventoryservice.entity.InventoryItem;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface InventoryMapper {

    InventoryResponse toResponse(InventoryItem item);
}
