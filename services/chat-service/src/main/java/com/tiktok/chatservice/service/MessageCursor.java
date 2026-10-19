package com.tiktok.chatservice.service;

import com.tiktok.chatservice.entity.Message;
import com.tiktok.chatservice.exception.InvalidCursorException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * Where the last page of a conversation stopped: {@code sentAt}, plus the {@code _id} that breaks
 * its ties.
 *
 * <p>Both halves are needed. sentAt alone is not unique — Mongo stores it to the millisecond, and
 * two people typing at once or one client sending a burst put several messages inside one — so a
 * {@code sentAt < cursor} range dropped every message sharing that millisecond with the previous
 * page's last row. They were simply missing from the history, with no gap to show for it. The id
 * makes the order total, which is the one thing a keyset needs. It is compared as a string, the
 * way Mongo sorts {@code _id} here: not numeric order for the Snowflake it holds, and it does not
 * have to be, as long as the range and the sort use the same order.
 */
record MessageCursor(Instant sentAt, String id) {

    private static final char SEPARATOR = '_';

    /**
     * Null for "start at the newest", which is what an absent cursor means. The cursor is client
     * input and comes back on every page request, so a mangled one is a routine 400 rather than the
     * 500 an unguarded parse produces.
     */
    static MessageCursor decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        int separator = raw.indexOf(SEPARATOR);
        if (separator < 0) {
            return decodeLegacy(raw);
        }
        if (separator == 0 || separator == raw.length() - 1) {
            throw new InvalidCursorException(raw);
        }

        try {
            long epochMilli = Long.parseLong(raw, 0, separator, 10);
            return new MessageCursor(Instant.ofEpochMilli(epochMilli), raw.substring(separator + 1));
        } catch (NumberFormatException e) {
            throw new InvalidCursorException(raw);
        }
    }

    static MessageCursor of(Message message) {
        return new MessageCursor(message.getSentAt(), message.getId());
    }

    /**
     * Epoch millis rather than an ISO string: millis are what BSON stores, so the value coming back
     * on the next request is the one the index is ordered on, exactly.
     */
    String encode() {
        return sentAt.toEpochMilli() + String.valueOf(SEPARATOR) + id;
    }

    /**
     * The format from before the id was part of it — Base64 of an ISO timestamp, whose alphabet
     * has no {@code _} — still held by any client that loaded a page before the deploy. Without an
     * id it pages the old way, strictly older than the timestamp, once: the cursor it gets back is
     * the new kind.
     */
    private static MessageCursor decodeLegacy(String raw) {
        try {
            String timestamp = new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8);
            return new MessageCursor(Instant.parse(timestamp), null);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new InvalidCursorException(raw);
        }
    }
}
