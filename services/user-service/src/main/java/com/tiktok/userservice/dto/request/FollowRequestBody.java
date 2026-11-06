package com.tiktok.userservice.dto.request;

/**
 * Optional body of {@code POST /users/{id}/follow}: the video the follower was watching, so the
 * creator's Studio credits it. Profile-page follows send no body at all.
 */
public record FollowRequestBody(Long sourceVideoId) {
}
