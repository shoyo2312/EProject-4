package com.tiktok.interactionservice.dto.response;

/**
 * @param watchedMs what the server recorded, which is the reported figure clamped to the video's
 *                  length — echoed back so a client can see its own number was adjusted
 * @param completed whether this counted as watching to the end, decided server-side so the
 *                  threshold can move without a client release
 */
public record WatchResponse(
        Long videoId,
        long watchedMs,
        boolean completed
) {
}
