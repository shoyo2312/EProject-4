package com.tiktok.chatservice.dto.response;

import com.tiktok.chatservice.entity.ReceiptMark;

/**
 * How far one participant has got in a conversation: {@code delivered} is the newest message their
 * client has received and acknowledged, {@code read} the newest one they actually looked at. Either
 * is null until they report. Seeing implies receiving, so {@code delivered} is never behind
 * {@code read}.
 *
 * <p>A sender works out the state of each of their own messages by position: delivered when the
 * message is at or before {@code delivered}, seen when it is at or before {@code read} — never by
 * comparing times, since the time a mark was recorded says nothing about which messages it covers.
 */
public record ReceiptView(String conversationId, Long userId, ReceiptMark delivered, ReceiptMark read) {
}
