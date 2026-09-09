package com.tiktok.mediaworker.service;

import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.event.video.VideoModerationCompletedEvent;
import com.tiktok.mediaworker.client.ModerationClient;
import com.tiktok.mediaworker.client.ModerationResponse;
import com.tiktok.mediaworker.client.ModerationUnavailableException;
import com.tiktok.mediaworker.config.MinioProperties;
import com.tiktok.mediaworker.config.ModerationProperties;
import io.minio.DownloadObjectArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The property that matters here is that every path produces a verdict. A video with no verdict
 * sits at PENDING_MODERATION with nothing left in the system that would ever move it, so the
 * interesting cases are all the ways this can fail rather than the one where it works.
 */
@ExtendWith(MockitoExtension.class)
class ModerationServiceImplTest {

    private static final MinioProperties MINIO =
            new MinioProperties("http://localhost:9000", "key", "secret", "video-media");
    private static final ModerationProperties ENABLED =
            new ModerationProperties(true, "http://moderation:8099", 10, 30_000L, 3, 0L);

    @Mock
    private MinioClient minioClient;
    @Mock
    private ModerationClient moderationClient;
    @Mock
    private Ffmpeg ffmpeg;

    @Test
    void moderate_passesTheModelsVerdictAndScoresThrough() throws Exception {
        givenTheFileDownloads();
        givenFramesAreSampled(3);
        when(moderationClient.moderate(any())).thenReturn(new ModerationResponse(
                "REJECTED", "nsfw", 0.97, 2, 3, 812L, "Falconsai/nsfw_image_detection", "nsfw-v1"));

        VideoModerationCompletedEvent event = service(ENABLED).moderate("vid123", 60);

        assertThat(event.verdict()).isEqualTo(ModerationVerdict.REJECTED);
        assertThat(event.maxScore()).isEqualTo(0.97);
        assertThat(event.suspiciousFrames()).isEqualTo(2);
        assertThat(event.totalFrames()).isEqualTo(3);
        assertThat(event.modelVersion()).isEqualTo("nsfw-v1");
        assertThat(event.reason()).isNull();
    }

    @Test
    void moderate_whenTheServiceIsUnreachable_retriesAndThenAsksForAHuman() throws Exception {
        givenTheFileDownloads();
        givenFramesAreSampled(3);
        when(moderationClient.moderate(any()))
                .thenThrow(new ModerationUnavailableException("connection refused"));

        VideoModerationCompletedEvent event = service(ENABLED).moderate("vid123", 60);

        verify(moderationClient, times(3)).moderate(any());
        assertThat(event.verdict())
                .as("an unreachable classifier must never publish and never remove")
                .isEqualTo(ModerationVerdict.REVIEW);
        assertThat(event.reason()).contains("connection refused");
    }

    @Test
    void moderate_whenTheFirstAttemptFails_theSecondStillDecides() throws Exception {
        givenTheFileDownloads();
        givenFramesAreSampled(2);
        when(moderationClient.moderate(any()))
                .thenThrow(new ModerationUnavailableException("timed out"))
                .thenReturn(new ModerationResponse("APPROVED", "nsfw", 0.02, 0, 2, 300L, "m", "v"));

        VideoModerationCompletedEvent event = service(ENABLED).moderate("vid123", 60);

        assertThat(event.verdict()).isEqualTo(ModerationVerdict.APPROVED);
    }

    @Test
    void moderate_whenNoFrameCouldBeSampled_asksForAHumanWithoutCallingTheModel() throws Exception {
        givenTheFileDownloads();
        when(ffmpeg.sampleFrames(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        VideoModerationCompletedEvent event = service(ENABLED).moderate("vid123", 60);

        verify(moderationClient, never()).moderate(any());
        assertThat(event.verdict()).isEqualTo(ModerationVerdict.REVIEW);
        assertThat(event.totalFrames()).isZero();
    }

    @Test
    void moderate_whenTheVerdictIsSomethingThisBuildDoesNotKnow_treatsItAsReview() throws Exception {
        givenTheFileDownloads();
        givenFramesAreSampled(1);
        when(moderationClient.moderate(any())).thenReturn(
                new ModerationResponse("QUARANTINED", "nsfw", 0.5, 1, 1, 100L, "m", "v"));

        assertThat(service(ENABLED).moderate("vid123", 60).verdict()).isEqualTo(ModerationVerdict.REVIEW);
    }

    @Test
    void moderate_whenDisabled_approvesWithoutTouchingStorageOrTheModel() throws Exception {
        ModerationProperties disabled =
                new ModerationProperties(false, "http://moderation:8099", 10, 30_000L, 3, 0L);

        VideoModerationCompletedEvent event = service(disabled).moderate("vid123", 60);

        assertThat(event.verdict()).isEqualTo(ModerationVerdict.APPROVED);
        assertThat(event.reason()).contains("disabled");
        verify(minioClient, never()).downloadObject(any());
        verify(moderationClient, never()).moderate(any());
    }

    /** A null duration must not blow up the interval arithmetic — older events carry no length. */
    @Test
    void moderate_withoutADuration_stillSamplesAndDecides() throws Exception {
        givenTheFileDownloads();
        givenFramesAreSampled(1);
        when(moderationClient.moderate(any()))
                .thenReturn(new ModerationResponse("APPROVED", "nsfw", 0.01, 0, 1, 90L, "m", "v"));

        assertThat(service(ENABLED).moderate("vid123", null).verdict()).isEqualTo(ModerationVerdict.APPROVED);
    }

    private ModerationServiceImpl service(ModerationProperties properties) {
        return new ModerationServiceImpl(minioClient, MINIO, properties, moderationClient, ffmpeg);
    }

    private void givenTheFileDownloads() throws Exception {
        doAnswer(invocation -> {
            DownloadObjectArgs args = invocation.getArgument(0);
            Files.writeString(Path.of(args.filename()), "not really an mp4");
            return null;
        }).when(minioClient).downloadObject(any(DownloadObjectArgs.class));
    }

    private void givenFramesAreSampled(int count) {
        when(ffmpeg.sampleFrames(any(), any(), anyInt(), anyInt())).thenAnswer(invocation -> {
            Path directory = invocation.getArgument(1);
            java.util.List<Path> frames = new java.util.ArrayList<>();
            for (int index = 0; index < count; index++) {
                Path frame = directory.resolve("frame-%03d.jpg".formatted(index + 1));
                Files.write(frame, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) index});
                frames.add(frame);
            }
            return frames;
        });
    }
}
