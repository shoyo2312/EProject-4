package com.tiktok.chatservice.realtime;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One message on {@code /topic/users.{userId}}. A snapshot, never a delta — same reasoning as
 * {@link VideoFrame}. Unset fields are dropped from the JSON: a follow-only update carries no
 * {@code totalLikes}, and the FE keeps whatever value it already had rather than being handed a
 * zero it would mistake for real.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserFrame(
        String type,
        String userId,
        Long followerCount,
        Long followingCount,
        Long totalLikes
) {

    public static UserFrame stats(String userId, Long followerCount, Long followingCount, Long totalLikes) {
        return new UserFrame("stats", userId, followerCount, followingCount, totalLikes);
    }
}
