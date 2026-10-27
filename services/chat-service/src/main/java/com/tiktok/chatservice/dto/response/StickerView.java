package com.tiktok.chatservice.dto.response;

/**
 * A sticker as a client draws it. {@code url} is an unsigned public URL that keeps working for as
 * long as the object exists — never a presigned one, because messages outlive any signature.
 */
public record StickerView(
        String stickerId,
        String name,
        String url,
        int width,
        int height
) {
}
