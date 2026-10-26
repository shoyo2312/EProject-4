package com.tiktok.videoservice.service;

import com.tiktok.moderation.rules.KeywordFilter;
import com.tiktok.videoservice.client.FeedKeywords;
import com.tiktok.videoservice.client.UserSettingsClient;
import com.tiktok.videoservice.dto.request.FeedKind;
import com.tiktok.videoservice.dto.response.VideoResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Settings → Filter keywords: drops the videos matching the viewer's keywords for one feed. Matched
 * like a creator's comment keywords (moderation-lib {@link KeywordFilter}) over title, description
 * and tags, so case and diacritics behave the same in both places.
 *
 * <p>Fails open: when user-service cannot say what the viewer filters, the page is served as it is.
 * This is a preference, not a safety control, and an empty feed is the worse answer.
 */
@Component
@RequiredArgsConstructor
public class FeedKeywordFilter {

    private final UserSettingsClient userSettingsClient;

    /** {@code videos} unchanged for an anonymous viewer, a read that names no feed, or no keywords. */
    public List<VideoResponse> apply(Long viewerId, FeedKind feed, List<VideoResponse> videos) {
        if (viewerId == null || feed == null || videos.isEmpty()) {
            return videos;
        }
        FeedKeywords keywords = userSettingsClient.feedKeywords(viewerId);
        if (keywords == null || keywords.forFeed(feed) == null || keywords.forFeed(feed).isEmpty()) {
            return videos;
        }
        // "#cat" is the tag cat: the matcher reads the text without its #, so the keyword goes without too.
        List<String> words = keywords.forFeed(feed).stream()
                .map(keyword -> keyword.startsWith("#") ? keyword.substring(1) : keyword)
                .toList();
        return videos.stream()
                .filter(video -> KeywordFilter.match(textOf(video), words).isEmpty())
                .toList();
    }

    private static String textOf(VideoResponse video) {
        return Stream.of(video.title(), video.description(),
                        video.tags() == null ? null : String.join(" ", video.tags()))
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
    }
}
