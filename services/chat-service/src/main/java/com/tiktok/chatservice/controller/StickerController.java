package com.tiktok.chatservice.controller;

import com.tiktok.chatservice.dto.response.StickerPackResponse;
import com.tiktok.chatservice.dto.response.StickerView;
import com.tiktok.chatservice.dto.response.SuggestionTopicView;
import com.tiktok.chatservice.sticker.GiphyClient;
import com.tiktok.chatservice.sticker.StickerCatalog;
import com.tiktok.chatservice.sticker.StickerSuggestions;
import com.tiktok.chatservice.sticker.SuggestionTopics;
import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final GiphyClient giphyClient;
    private final SuggestionTopics suggestionTopics;
    private final StickerSuggestions stickerSuggestions;

    @GetMapping
    public ApiResponse<List<StickerPackResponse>> packs() {
        var pack = stickerCatalog.pack();
        return ApiResponse.success(pack.stickers().isEmpty() ? List.of() : List.of(pack));
    }

    /** GIPHY stickers for the picker: a search, or the trending ones when {@code q} is empty. */
    @GetMapping("/giphy")
    public ApiResponse<List<StickerView>> giphy(@RequestParam(defaultValue = "") String q,
                                                @RequestParam(defaultValue = "0") int offset,
                                                @RequestParam(defaultValue = "24") int limit) {
        return ApiResponse.success(giphyClient.stickers(q, offset, limit));
    }

    /**
     * The words that earn a suggestion while typing. The client matches what is typed against them,
     * so the text never comes here; it asks for a topic by id once one matches.
     */
    @GetMapping("/suggestions/topics")
    public ApiResponse<List<SuggestionTopicView>> suggestionTopics() {
        return ApiResponse.success(suggestionTopics.views());
    }

    /** A few stickers for one topic, served from a pool refreshed a few times a day. Unknown topic: none. */
    @GetMapping("/suggestions/{topicId}")
    public ApiResponse<List<StickerView>> suggestions(@PathVariable String topicId) {
        return ApiResponse.success(stickerSuggestions.suggestions(topicId));
    }
}
