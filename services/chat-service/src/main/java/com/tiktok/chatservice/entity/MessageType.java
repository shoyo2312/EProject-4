package com.tiktok.chatservice.entity;

/**
 * What a message is. Emoji are not a type: they are characters inside the content of a TEXT message.
 * Messages stored before types existed have none, and are TEXT — see {@link Message#getType()}.
 */
public enum MessageType {
    TEXT,
    STICKER,
    VIDEO
}
