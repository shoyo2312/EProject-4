package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.request.SaveKeywordFilterRequest;
import com.tiktok.userservice.dto.response.KeywordFilterResponse;

import java.util.List;

/** Settings → Content preferences → Filter keywords. video-service enforces them; see docs/user-settings.md. */
public interface FeedKeywordFilterService {

    /** Per account (our call — TikTok's limit is not observable). */
    int MAX_FILTERS = 100;

    int MAX_KEYWORD_CODE_POINTS = 200;

    /** The caller's filters, newest first. */
    List<KeywordFilterResponse> list(Long userId);

    /**
     * Adds a filter: one word or one hashtag, at most 200 characters, something to match, at least
     * one feed — otherwise {@code VALIDATION_ERROR}. {@code KEYWORD_FILTER_DUPLICATE} when the account
     * already has the keyword in any capitalization, {@code TOO_MANY_KEYWORD_FILTERS} past the cap.
     */
    KeywordFilterResponse create(Long userId, SaveKeywordFilterRequest request);

    /** Same rules as {@link #create}. {@code KEYWORD_FILTER_NOT_FOUND} for an id the caller does not own. */
    KeywordFilterResponse update(Long userId, Long id, SaveKeywordFilterRequest request);

    /** Soft delete; the keyword can be added again afterwards. */
    void delete(Long userId, Long id);
}
