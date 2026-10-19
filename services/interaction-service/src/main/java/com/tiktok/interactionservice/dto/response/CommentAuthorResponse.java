package com.tiktok.interactionservice.dto.response;

/**
 * Who wrote a comment, for a service that has to reach them about it — notification-service
 * telling the author of a reported comment that an admin warned them. The ids come back beside the
 * author so a caller holding several answers can tell them apart.
 */
public record CommentAuthorResponse(
        Long videoId,
        Long commentId,
        Long userId
) {
}
