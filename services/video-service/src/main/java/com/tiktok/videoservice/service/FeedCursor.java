package com.tiktok.videoservice.service;

import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.exception.InvalidFeedCursorException;

import java.time.Instant;

/**
 * Where the last feed page stopped: {@code createdAt}, plus the {@code _id} that breaks its ties.
 *
 * <p>Both halves are needed. createdAt alone is not unique — Mongo stores it to the millisecond and
 * two uploads can land inside one — so a {@code createdAt < cursor} range would drop every video
 * sharing that millisecond with the previous page's last row, and {@code <=} would repeat them.
 * The id is what makes the ordering total, which is the one thing a keyset requires.
 *
 * <p>That id is compared as a string, matching how Mongo sorts {@code _id} here. It is not numeric
 * order for the Snowflake the string holds, and it does not need to be: the tiebreak only has to be
 * <em>the same</em> total order the sort uses, or range and sort would disagree and pages would
 * skip. It only ever separates rows already equal on createdAt.
 */
record FeedCursor(Instant createdAt, String id) {

    private static final char SEPARATOR = '_';

    /** Null for "start at the newest", which is what an absent cursor means. */
    static FeedCursor decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        int separator = raw.indexOf(SEPARATOR);
        if (separator <= 0 || separator == raw.length() - 1) {
            throw new InvalidFeedCursorException();
        }

        try {
            long epochMilli = Long.parseLong(raw, 0, separator, 10);
            return new FeedCursor(Instant.ofEpochMilli(epochMilli), raw.substring(separator + 1));
        } catch (NumberFormatException e) {
            throw new InvalidFeedCursorException();
        }
    }

    static FeedCursor of(Video video) {
        return new FeedCursor(video.getCreatedAt(), video.getId());
    }

    /**
     * Epoch millis rather than an ISO string: millis are what BSON stores, so the value coming back
     * on the next request is the one the index is ordered on, exactly. A formatted timestamp would
     * invite a client to "helpfully" reformat it and land between two rows.
     */
    String encode() {
        return createdAt.toEpochMilli() + String.valueOf(SEPARATOR) + id;
    }
}
