package com.tiktok.chatservice.controller;

import com.tiktok.chatservice.dto.response.StickerPackResponse;
import com.tiktok.chatservice.sticker.StickerCatalog;
import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * What can be sent. A literal path under {@code /api/v1/conversations}, so the gateway route that
 * already carries chat covers it and it outranks {@code /{conversationId}}.
 */
@RestController
@RequestMapping("/api/v1/conversations/stickers")
@RequiredArgsConstructor
public class StickerController {

    private final StickerCatalog stickerCatalog;

    @GetMapping
    public ApiResponse<List<StickerPackResponse>> packs() {
        return ApiResponse.success(List.of(stickerCatalog.pack()));
    }
}
