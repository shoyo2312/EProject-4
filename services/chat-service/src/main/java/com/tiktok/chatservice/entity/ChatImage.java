package com.tiktok.chatservice.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A picture sent in a conversation. The bytes live here, in the chat database, and are served only by
 * the authenticated image endpoint — never from a public bucket — so seeing a picture needs the same
 * right as seeing the conversation.
 *
 * <p>An upload that no message has claimed carries {@code expiresAt}, and Mongo's TTL monitor removes
 * it; claiming it ({@code messageId} set) clears the field. A recall deletes the row.
 */
@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "chat_images")
public class ChatImage {

    @Id
    private String id;

    @Indexed
    private String conversationId;

    private Long uploaderId;

    /** Set once a message carries the picture. */
    @Indexed(sparse = true)
    private String messageId;

    /** The sniffed type (image/jpeg, image/png, image/gif), never the one the client claimed. */
    private String mime;

    private int width;

    private int height;

    private long size;

    private byte[] data;

    private Instant createdAt;

    @Indexed(expireAfterSeconds = 0)
    private Instant expiresAt;
}
