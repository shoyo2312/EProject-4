package com.tiktok.userservice.dto.response;

public record BlockStateResponse(Long userId, boolean blockedByViewer, boolean blockedViewer, long version) {
}
