package com.tiktok.inventoryservice.dto.request;

import jakarta.validation.constraints.Min;

public record RestockRequest(
        @Min(1) int quantity
) {
}
