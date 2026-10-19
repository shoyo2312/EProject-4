package com.tiktok.videoservice.service;

import com.tiktok.videoservice.config.MinioProperties;
import com.tiktok.videoservice.dto.response.VideoResponse;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import java.time.Duration;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The by-id admin read exists to answer for videos every other route hides. If it ever picks up a
 * visibility rule, the console silently loses the previews a moderator most needs — a reported
 * video that was already taken down, or one its owner deleted right after being reported.
 */
class AdminVideoDirectoryTest {

    private static final Instant DELETED_AT = Instant.parse("2026-09-01T00:00:00Z");

    private final VideoRepository videoRepository = mock(VideoRepository.class);
    private static final MinioProperties MINIO = new MinioProperties(
            "http://localhost:9000", "http://localhost:9000", "key", "secret", "video-media", "us-east-1", Duration.ofMinutes(15));

    // Real client, so the URLs are really signed: presigning is local arithmetic. Spied only for
    // the one call that would leave the test, the check that the quarantined copy is there.
    private final MinioClient minioClient = spy(MinioClient.builder().endpoint(MINIO.endpoint())
            .credentials(MINIO.accessKey(), MINIO.secretKey()).region(MINIO.region()).build());

    private final AdminVideoDirectory directory = new AdminVideoDirectory(videoRepository, new VideoMapperImpl(),
            new QuarantinedMediaUrls(minioClient, MINIO));

    /** Every status is in the answer, so the console never reads a missing key as a count. */
    @Test
    void statusTotalsNameEveryStatusAndTrimTheSearch() {
        when(videoRepository.countForAdminByStatus("cats", null, null, null))
                .thenReturn(java.util.Map.of(VideoStatus.PUBLISHED, 3L));

        assertThat(directory.countByStatus("  cats ", null, null, null))
                .hasSize(VideoStatus.values().length)
                .containsEntry(VideoStatus.PUBLISHED, 3L)
                .containsEntry(VideoStatus.PENDING_REVIEW, 0L);

        directory.countByStatus("   ", null, null, null);
        verify(videoRepository).countForAdminByStatus(null, null, null, null);
    }

    private void quarantinedCopy(boolean present) throws Exception {
        if (present) {
            doReturn(mock(StatObjectResponse.class)).when(minioClient).statObject(any(StatObjectArgs.class));
        } else {
            doThrow(new ErrorResponseException(
                    new ErrorResponse("NoSuchKey", "not found", "video-media", "k", "k", "r", "h"), null, null))
                    .when(minioClient).statObject(any(StatObjectArgs.class));
        }
    }

    private Video withMedia(VideoStatus status) {
        return Video.builder()
                .id("1")
                .userId(7L)
                .status(status)
                .visibility(VideoVisibility.PUBLIC)
                .hlsUrl("http://localhost:9000/video-media/hls/1/source.mp4")
                .thumbnailUrl("http://localhost:9000/video-media/thumbnails/1.jpg")
                .build();
    }

    /**
     * media-worker moves a rejected or taken-down video's media under quarantine/, which is not
     * publicly readable, so the stored URLs 404 for everyone — including the moderator deciding
     * whether to restore it. The console gets short-lived signed URLs to the quarantined copies.
     */
    @Test
    void quarantinedVideo_isServedToTheConsoleThroughSignedQuarantineUrls() throws Exception {
        quarantinedCopy(true);
        when(videoRepository.findById("1")).thenReturn(Optional.of(withMedia(VideoStatus.TAKEN_DOWN)));

        VideoResponse response = directory.getById("1");

        assertThat(response.hlsUrl())
                .startsWith("http://localhost:9000/video-media/quarantine/hls/1/source.mp4?")
                .contains("X-Amz-Signature=");
        assertThat(response.thumbnailUrl()).startsWith("http://localhost:9000/video-media/quarantine/thumbnails/1.jpg?");
        assertThat(response.previewUrl()).isNull();
    }

    @Test
    void rejectedVideo_isQuarantinedToo() throws Exception {
        quarantinedCopy(true);
        when(videoRepository.findById("1")).thenReturn(Optional.of(withMedia(VideoStatus.REJECTED)));

        assertThat(directory.getById("1").hlsUrl()).contains("/quarantine/hls/1/source.mp4?");
    }

    /** media-worker now holds a REVIEW verdict's media too; the moderator deciding it still has to watch it. */
    @Test
    void videoHeldForReview_isQuarantinedToo() throws Exception {
        quarantinedCopy(true);
        when(videoRepository.findById("1")).thenReturn(Optional.of(withMedia(VideoStatus.PENDING_REVIEW)));

        assertThat(directory.getById("1").hlsUrl()).contains("/quarantine/hls/1/source.mp4?");
    }

    /** And the owner's deletion, whatever the status: the trash window is when the console reviews it. */
    @Test
    void videoItsOwnerDeleted_isQuarantinedToo() throws Exception {
        quarantinedCopy(true);
        Video deleted = withMedia(VideoStatus.PUBLISHED);
        deleted.markDeleted();
        when(videoRepository.findById("1")).thenReturn(Optional.of(deleted));

        assertThat(directory.getById("1").hlsUrl()).contains("/quarantine/hls/1/source.mp4?");
    }

    /**
     * Held or deleted before media-worker quarantined those, or before it has consumed the
     * decision: the media is still where it was, and a signed URL to the empty quarantine key would
     * leave the moderator nothing to watch.
     */
    @Test
    void mediaNotMovedYet_isServedFromWhereItIs() throws Exception {
        quarantinedCopy(false);
        when(videoRepository.findById("1")).thenReturn(Optional.of(withMedia(VideoStatus.PENDING_REVIEW)));

        assertThat(directory.getById("1").hlsUrl()).isEqualTo("http://localhost:9000/video-media/hls/1/source.mp4");
    }

    @Test
    void publishedVideo_keepsItsPublicUrls() throws Exception {
        when(videoRepository.findById("1")).thenReturn(Optional.of(withMedia(VideoStatus.PUBLISHED)));

        assertThat(directory.getById("1").hlsUrl()).isEqualTo("http://localhost:9000/video-media/hls/1/source.mp4");
        verify(minioClient, never()).statObject(any(StatObjectArgs.class));
    }

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
