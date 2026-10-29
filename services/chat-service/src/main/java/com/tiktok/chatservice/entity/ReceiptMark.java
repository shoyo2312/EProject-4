package com.tiktok.chatservice.entity;

import java.time.Instant;

/**
 * A position in a conversation that one participant's client has reached: {@code messageId} and
 * {@code sentAt} name the newest message of the other side it covers (messages are ordered by
 * {@code (sentAt, id)}, so two sent in the same millisecond still have an order), and {@code at} is
 * when the server recorded it, on the server's own clock.
 *
 * <p>The position is what proves anything; {@code at} is only what "seen 13 minutes ago" is read
 * from. A mark only moves to a later position, so repeating one never changes {@code at}.
 */
public record ReceiptMark(String messageId, Instant sentAt, Instant at) {
}
