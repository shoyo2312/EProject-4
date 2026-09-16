package com.tiktok.interactionservice.dto.response;

public record RepostStatusResponse(
        Long videoId,
        boolean reposted,
        long repostCount
) {
}
