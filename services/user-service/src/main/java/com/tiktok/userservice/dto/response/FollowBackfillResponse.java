package com.tiktok.userservice.dto.response;

/** How many live follow edges the backfill wrote to the outbox. */
public record FollowBackfillResponse(long published) {
}
