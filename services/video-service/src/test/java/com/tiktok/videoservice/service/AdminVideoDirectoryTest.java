package com.tiktok.videoservice.service;

import com.tiktok.videoservice.dto.response.VideoResponse;
import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
import com.tiktok.videoservice.exception.VideoNotFoundException;
import com.tiktok.videoservice.mapper.VideoMapperImpl;
import com.tiktok.videoservice.repository.VideoRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The by-id admin read exists to answer for videos every other route hides. If it ever picks up a
 * visibility rule, the console silently loses the previews a moderator most needs — a reported
 * video that was already taken down, or one its owner deleted right after being reported.
 */
class AdminVideoDirectoryTest {

    private static final Instant DELETED_AT = Instant.parse("2026-09-01T00:00:00Z");

    private final VideoRepository videoRepository = mock(VideoRepository.class);
    private final AdminVideoDirectory directory =
            new AdminVideoDirectory(videoRepository, new VideoMapperImpl());

    private Video video(VideoStatus status, VideoVisibility visibility, Instant deletedAt) {
        return Video.builder()
                .id("1")
                .userId(7L)
                .title("Removed for spam")
                .status(status)
                .visibility(visibility)
                .deletedAt(deletedAt)
                .build();
    }

    @Test
    void answersForAVideoThePublicRouteWouldHide() {
        when(videoRepository.findById("1"))
                .thenReturn(Optional.of(video(VideoStatus.TAKEN_DOWN, VideoVisibility.PRIVATE, null)));

        VideoResponse response = directory.getById("1");

        assertThat(response.status()).isEqualTo(VideoStatus.TAKEN_DOWN);
        assertThat(response.visibility()).isEqualTo(VideoVisibility.PRIVATE);
    }

    /** The listing drops these, so nothing but this route can show a moderator what was reported. */
    @Test
    void answersForAVideoItsOwnerDeletedAndSaysSo() {
        when(videoRepository.findById("1"))
                .thenReturn(Optional.of(video(VideoStatus.PUBLISHED, VideoVisibility.PUBLIC, DELETED_AT)));

        assertThat(directory.getById("1").deletedAt()).isEqualTo(DELETED_AT);
    }

    @Test
    void refusesAnIdThatResolvesToNothing() {
        when(videoRepository.findById("2")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> directory.getById("2"))
                .isInstanceOf(VideoNotFoundException.class);
    }
}
