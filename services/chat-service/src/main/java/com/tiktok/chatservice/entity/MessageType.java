package com.tiktok.chatservice.entity;

/**
 * What a message is. Emoji are not a type: they are characters inside the content of a TEXT message.
 * Messages stored before types existed have none, and are TEXT — see {@link Message#getType()}.
 */
public enum MessageType {
    TEXT,
    STICKER,
    VIDEO,
    /** A text answer to someone's story: content is the text, storyId the story. */
    STORY_REPLY,
    /** One picture (imageId, in the chat database) with an optional caption in content. */
    IMAGE
}
