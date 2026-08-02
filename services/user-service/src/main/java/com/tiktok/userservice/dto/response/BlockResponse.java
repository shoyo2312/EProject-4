package com.tiktok.userservice.dto.response;

public record BlockResponse(
        Long blockerId,
        Long blockedId
) {
}
