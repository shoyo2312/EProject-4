package com.tiktok.event.interaction;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

public record VideoRepostedEvent(
        String eventId,
        Instant occurredAt,
        Long videoId,
        Long userId,
        boolean reposted
) implements DomainEvent {

    public static VideoRepostedEvent of(Long videoId, Long userId, boolean reposted) {
        return new VideoRepostedEvent(UUID.randomUUID().toString(), Instant.now(), videoId, userId, reposted);
    }
}
