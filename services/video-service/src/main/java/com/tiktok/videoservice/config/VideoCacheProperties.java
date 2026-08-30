package com.tiktok.videoservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The video metadata cache in front of {@code getById} and {@code getByIds} — the two reads a
 * feed scroll repeats for every viewer of the same video.
 *
 * @param enabled false takes every read straight to Mongo. Here so an environment can turn the
 *                cache off without removing Redis from the service, which is what you want on
 *                the day a stale entry is the leading suspect.
 * @param ttl     deliberately short. The value of this cache comes from many viewers hitting one
 *                hot video, not from one viewer revisiting it: at a thousand requests a minute a
 *                sixty-second entry already serves all but the first, so a longer TTL buys almost
 *                no extra hits while multiplying how stale a counter can get. viewCount,
 *                likeCount and commentCount are $inc-ed straight into Mongo by three Kafka
 *                consumers that evict nothing, so this TTL <em>is</em> the bound on how wrong a
 *                displayed count may be. Status changes do not wait for it — they evict.
 */
@ConfigurationProperties(prefix = "app.cache.video")
public record VideoCacheProperties(
        boolean enabled,
        Duration ttl
) {
}
