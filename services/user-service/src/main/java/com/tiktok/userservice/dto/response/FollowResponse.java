package com.tiktok.userservice.dto.response;

public record FollowResponse(
        Long followerId,
        Long followingId
) {
}
