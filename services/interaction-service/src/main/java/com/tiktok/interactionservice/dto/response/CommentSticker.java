package com.tiktok.interactionservice.dto.response;

/**
 * The sticker on a comment, ready to draw. {@code url} is public and unsigned — the bucket's
 * {@code stickers/} prefix is readable by anyone — the same URL chat-service hands out for it.
 */
public record CommentSticker(
        String stickerId,
        String url,
        int width,
        int height
) {
}
