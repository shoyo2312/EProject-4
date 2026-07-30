package com.tiktok.chatservice.dto.response;

import java.util.List;

public record MessagePageResponse(
        List<MessageResponse> items,
        String nextCursor,
        boolean hasMore
) {
}
