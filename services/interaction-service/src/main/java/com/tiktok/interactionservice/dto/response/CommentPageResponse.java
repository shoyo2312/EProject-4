package com.tiktok.interactionservice.dto.response;

import java.util.List;

public record CommentPageResponse(
        List<CommentResponse> items,
        String nextCursor,
        boolean hasMore
) {
}
