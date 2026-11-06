package com.tiktok.interactionservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where a comment's sticker image is served from — the same values chat-service uses for its own
 * sticker URLs ({@code chat.stickers}), so a sticker looks the same in a comment and in a message.
 *
 * @param publicBaseUrl what a client uses to reach MinIO, not the in-cluster address
 * @param bucket        the bucket whose {@code stickers/} prefix is publicly readable
 */
@ConfigurationProperties(prefix = "interaction.comment.stickers")
public record CommentStickerProperties(String publicBaseUrl, String bucket) {
}
