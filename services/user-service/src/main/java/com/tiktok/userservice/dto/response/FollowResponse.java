package com.tiktok.userservice.dto.response;

public record FollowResponse(
        Long followerId,
        Long followingId,
        /** FOLLOWING when the edge exists now; REQUESTED when a private account's owner has to approve. */
        Status status
) {

    public enum Status {
        FOLLOWING,
        REQUESTED
    }
}
