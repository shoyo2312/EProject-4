package com.tiktok.notificationservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterDeviceRequest(
        @NotBlank(message = "token is required")
        @Size(max = 4096, message = "token is too long")
        String token
) {
}
