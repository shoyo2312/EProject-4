package com.tiktok.recommendationservice.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Every Redis key this service owns, in one place, so the write side and the read side cannot
 * drift apart on a name. Nothing here talks to Redis — the commands stay in the services,
 * because the write side is fed by Kafka listeners and the read side by an HTTP request, and
 * the two have nothing else in common.
 */
public final class RecoKeys {

    /**
     * Engagement is bucketed per hour rather than accumulated into one lifetime sorted set. A
     * cumulative score can only ever grow, so a video that went viral last month outranks one
     * that is going viral right now, forever. With buckets the old hours simply fall out of the
     * window instead of having to be subtracted.
     */
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC);

    /** How many hourly buckets the trending union spans. */
    public static final int TRENDING_WINDOW_HOURS = 24;

    /** One hour past the window, so a bucket cannot expire while the union still reads it. */
    public static final Duration BUCKET_TTL = Duration.ofHours(TRENDING_WINDOW_HOURS + 1L);

    /** Long enough that a lapsed viewer keeps their taste profile, short enough to reclaim it. */
    public static final Duration PROFILE_TTL = Duration.ofDays(30);

    /** Newest N entries kept in the per-user seen list and the per-tag video index. */
    public static final long TRIM_TO = 1000;

    /** Ceiling on the two global engagement counters, trimmed lowest-first by the rebuild job. */
    public static final long QUALITY_TRIM_TO = 100_000;

    private RecoKeys() {
    }

    /** Engagement collected during one UTC hour. */
    public static String trendBucket(Instant at) {
        return "reco:trend:" + HOUR.format(at);
    }

    /** The buckets a trending rebuild reads, newest first — index 0 is the current hour. */
    public static Stream<String> trendingWindow(Instant now) {
        return IntStream.range(0, TRENDING_WINDOW_HOURS)
                .mapToObj(hoursAgo -> trendBucket(now.minus(Duration.ofHours(hoursAgo))));
    }

    /** Decayed union of the window. Rebuilt on a schedule; read by both /trending and /feed. */
    public static final String TRENDING = "reco:trending";

    /** Tags of one video, as published. The only content feature this service has. */
    public static String videoTags(String videoId) {
        return "reco:video:tags:" + videoId;
    }

    /** Recently published videos carrying one tag, scored by publish time. */
    public static String tagIndex(String tag) {
        return "reco:tag:" + tag;
    }

    /** One viewer's tag affinity, scored by how much of each tagged video they actually watched. */
    public static String userTags(Long userId) {
        return "reco:user:tags:" + userId;
    }

    /** Videos one viewer has already been served content for, scored by epoch second. */
    public static String userSeen(Long userId) {
        return "reco:user:seen:" + userId;
    }

    /**
     * Global watch and completion counters, kept as two sorted sets rather than a hash per video
     * so the feed can read every candidate's numbers in two ZMSCORE calls instead of one round
     * trip per candidate.
     */
    public static final String VIDEO_WATCHES = "reco:video:watches";
    public static final String VIDEO_COMPLETIONS = "reco:video:completions";
}
