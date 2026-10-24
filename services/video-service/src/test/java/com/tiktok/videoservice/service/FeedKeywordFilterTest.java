package com.tiktok.videoservice.service;

import com.tiktok.videoservice.client.FeedKeywords;
import com.tiktok.videoservice.client.UserSettingsClient;
import com.tiktok.videoservice.dto.request.FeedKind;
import com.tiktok.videoservice.dto.response.VideoResponse;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FeedKeywordFilterTest {

    private static final Long VIEWER = 5L;

    private final UserSettingsClient userSettingsClient = mock(UserSettingsClient.class);
    private final FeedKeywordFilter filter = new FeedKeywordFilter(userSettingsClient);

    private static VideoResponse video(String id, String title, String description, List<String> tags) {
        return new VideoResponse(id, 1L, title, description, null, null, null, null, null, null,
                VideoStatus.PUBLISHED, VideoVisibility.PUBLIC, 0, 0, 0L, false, tags, Instant.now(),
                null, null, null, null, null, null, null, null, false, false, null);
    }

    private void forYou(String... keywords) {
        when(userSettingsClient.feedKeywords(VIEWER)).thenReturn(new FeedKeywords(List.of(keywords), List.of(), List.of()));
    }

    private List<String> kept(FeedKind feed, VideoResponse... videos) {
        return filter.apply(VIEWER, feed, List.of(videos)).stream().map(VideoResponse::id).toList();
    }

    @Test
    void dropsATitleMatchCaseInsensitively() {
        forYou("CAT");

        assertThat(kept(FeedKind.FOR_YOU, video("a", "my cat", null, List.of()), video("b", "my dog", null, List.of())))
                .containsExactly("b");
    }

    @Test
    void dropsADescriptionMatch() {
        forYou("cat");

        assertThat(kept(FeedKind.FOR_YOU, video("a", "Sunday", "a cat on a mat", List.of()))).isEmpty();
    }

    /** "#cat" filters the tag cat, and "cat" filters a #cat written in the text. */
    @Test
    void hashtagKeywordMatchesThePlainTag() {
        forYou("#cat");
        assertThat(kept(FeedKind.FOR_YOU, video("a", "Sunday", null, List.of("cat")))).isEmpty();

        forYou("cat");
        assertThat(kept(FeedKind.FOR_YOU, video("b", "Sunday", "so cute #cat", List.of()))).isEmpty();
    }

    /** The same matcher as creator comment keywords: a keyword with diacritics wants those diacritics. */
    @Test
    void diacriticsMatchLikeCommentKeywords() {
        forYou("tạch");

        assertThat(kept(FeedKind.FOR_YOU, video("a", "tách trà", null, List.of()))).containsExactly("a");
        assertThat(kept(FeedKind.FOR_YOU, video("b", "thi tạch rồi", null, List.of()))).isEmpty();
    }

    @Test
    void onlyTheRequestedFeedsList() {
        when(userSettingsClient.feedKeywords(VIEWER)).thenReturn(new FeedKeywords(List.of(), List.of("cat"), List.of()));
        VideoResponse cat = video("a", "my cat", null, List.of());

        assertThat(kept(FeedKind.FOR_YOU, cat)).containsExactly("a");
        assertThat(kept(FeedKind.FRIENDS, cat)).containsExactly("a");
        assertThat(kept(FeedKind.FOLLOWING, cat)).isEmpty();
    }

    @Test
    void anonymousViewerIsUntouchedAndUserServiceNotAsked() {
        List<VideoResponse> page = List.of(video("a", "my cat", null, List.of()));

        assertThat(filter.apply(null, FeedKind.FOR_YOU, page)).isEqualTo(page);
        assertThat(filter.apply(VIEWER, null, page)).isEqualTo(page);
        verifyNoInteractions(userSettingsClient);
    }

    /** A preference, not a safety control: no answer serves the feed as it is rather than an empty one. */
    @Test
    void unknownKeywordsServeTheFeedUnfiltered() {
        when(userSettingsClient.feedKeywords(VIEWER)).thenReturn(null);

        assertThat(kept(FeedKind.FOR_YOU, video("a", "my cat", null, List.of()))).containsExactly("a");
    }
}
