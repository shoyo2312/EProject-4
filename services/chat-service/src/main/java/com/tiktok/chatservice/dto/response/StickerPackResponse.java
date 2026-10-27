package com.tiktok.chatservice.dto.response;

import java.util.List;

/** The stickers a client may offer for sending. Retired stickers are not listed, but still render in history. */
public record StickerPackResponse(
        String packId,
        String name,
        List<StickerView> stickers
) {
}
