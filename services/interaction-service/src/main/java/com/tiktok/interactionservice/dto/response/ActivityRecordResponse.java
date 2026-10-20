package com.tiktok.interactionservice.dto.response;

import java.time.Instant;

/** One like, save or repost: which video, and when. */
public record ActivityRecordResponse(
        Long videoId,
        Instant at
) {
}
