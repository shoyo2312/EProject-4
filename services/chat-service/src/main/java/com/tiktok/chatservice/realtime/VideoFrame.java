package com.tiktok.chatservice.realtime;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One message on {@code /topic/videos.{videoId}}. Two shapes share the record, told apart by
 * {@code type}: {@code "counts"} carries the numbers and {@code "state"} carries status or
 * visibility. Unset fields are dropped from the JSON, so a state frame is not a wall of zeros a
 * client might mistake for real counters.
 *
 * <p>{@code videoId} is a String because it holds a Snowflake, and JavaScript's
 * {@code JSON.parse} silently rounds a 64-bit integer.
 *
 * <p>The counts are a snapshot, never a delta. A dropped frame then costs one stale render until
 * the next event; a dropped delta would be a number that stays wrong until the page reloads,
 * which is the bug this whole feature exists to fix.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VideoFrame(
        String type,
        String videoId,
        Long likeCount,
        Long commentCount,
        Long shareCount,
        Long viewCount,
        Long saveCount,
        String status,
        String visibility
) {

    public static VideoFrame counts(String videoId, long likeCount, long commentCount,
                                    long shareCount, long viewCount, long saveCount) {
        return new VideoFrame("counts", videoId, likeCount, commentCount, shareCount,
                viewCount, saveCount, null, null);
    }

    public static VideoFrame status(String videoId, String status) {
        return new VideoFrame("state", videoId, null, null, null, null, null, status, null);
    }

    public static VideoFrame visibility(String videoId, String visibility) {
        return new VideoFrame("state", videoId, null, null, null, null, null, null, visibility);
    }
}
