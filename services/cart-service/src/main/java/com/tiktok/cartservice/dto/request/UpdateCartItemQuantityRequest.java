package com.tiktok.cartservice.dto.request;

import jakarta.validation.constraints.Min;

public record UpdateCartItemQuantityRequest(
        @Min(1) int quantity
) {
}
