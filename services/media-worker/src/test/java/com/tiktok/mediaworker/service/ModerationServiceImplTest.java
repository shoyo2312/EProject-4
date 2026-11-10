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
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
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
            new MinioProperties("http://localhost:9000", "http://localhost:9000", "key", "secret", "video-media");
    private static final ModerationProperties ENABLED =
            new ModerationProperties(true, "http://moderation:8099", 10, 30_000L, 3, 0L);

    private static final byte[] PLAYBACK = "not really an mp4".getBytes(StandardCharsets.UTF_8);
    /** Base64 "AQID", worked out by hand rather than with the encoder under test. */
    private static final byte[] THUMBNAIL = {1, 2, 3};
    /** Base64 "BAUG". */
    private static final byte[] PREVIEW = {4, 5, 6};

    /** Ten frames spread over a minute, none of them anywhere near a line. */
    private static final ModerationResponse SPREAD_APPROVED = new ModerationResponse(
            "APPROVED", "violence", 0.21, 0, 10, 700L, "openai/clip-vit-base-patch16", "nsfw-v1+harm-v2");

    @Mock
    private MinioClient minioClient;
    @Mock
    private ModerationClient moderationClient;
    @Mock
    private Ffmpeg ffmpeg;
    @Mock
    private VideoProbe videoProbe;

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

    /**
     * A takedown or deletion during the transcode has already moved the output under quarantine/.
     * Reading only the public key failed all three attempts and sent the video for review unscored.
     * The thumbnail and preview moved with it, and are read from there too.
     */
    @Test
    void moderate_readsAPlaybackFileThatIsAlreadyQuarantined() throws Exception {
        ErrorResponseException missing = mock(ErrorResponseException.class);
        when(missing.errorResponse()).thenReturn(
                new ErrorResponse("NoSuchKey", "gone", "video-media", "hls/vid123/source.mp4", null, null, null));
        List<String> asked = new java.util.ArrayList<>();
        doAnswer(invocation -> {
            DownloadObjectArgs args = invocation.getArgument(0);
            asked.add(args.object());
            if (!args.object().startsWith("quarantine/")) {
                throw missing;
            }
            Files.writeString(Path.of(args.filename()), "not really an mp4");
            return null;
        }).when(minioClient).downloadObject(any(DownloadObjectArgs.class));
        givenFramesAreSampled(3);
        when(moderationClient.moderate(any())).thenReturn(new ModerationResponse(
                "APPROVED", "normal", 0.02, 0, 3, 400L, "Falconsai/nsfw_image_detection", "nsfw-v1"));

        VideoModerationCompletedEvent event = service(ENABLED).moderate("vid123", 60);

        assertThat(event.verdict()).isEqualTo(ModerationVerdict.APPROVED);
        assertThat(event.totalFrames()).isEqualTo(3);
        assertThat(asked).containsExactly("hls/vid123/source.mp4", "quarantine/hls/vid123/source.mp4",
                "thumbnails/vid123.jpg", "quarantine/thumbnails/vid123.jpg",
                "previews/vid123.webp", "quarantine/previews/vid123.webp");
    }

    /**
     * The thumbnail is the frame at 1s and the preview runs from 1s to 4s, while ten frames spread
     * over a minute start at 3s and are 6s apart. A video explicit for its first two seconds was
     * approved, and went out on every feed card with exactly those seconds as its cover. The cover
     * can hold a video but never remove one, and the admin sees what the cover scored.
     */
    @Test
    void moderate_whenTheThumbnailOrPreviewIsRejected_holdsTheVideoOnTheirScores() throws Exception {
        givenTheBucketHolds(Map.of(
                "hls/vid123/source.mp4", PLAYBACK,
                "thumbnails/vid123.jpg", THUMBNAIL,
                "previews/vid123.webp", PREVIEW));
        givenFramesAreSampled(10);
        when(moderationClient.moderate(any())).thenReturn(SPREAD_APPROVED, new ModerationResponse(
                "REJECTED", "nsfw", 0.97, 12, 17, 450L, "Falconsai/nsfw_image_detection", "nsfw-v1+harm-v2"));

        VideoModerationCompletedEvent event = service(ENABLED).moderate("vid123", 60);

        assertThat(event.verdict())
                .as("the thumbnail and preview can hold a video, never remove it")
                .isEqualTo(ModerationVerdict.REVIEW);
        assertThat(event).usingRecursiveComparison().ignoringFields("eventId", "occurredAt")
                .isEqualTo(VideoModerationCompletedEvent.of("vid123", ModerationVerdict.REVIEW, "nsfw", 0.97, 12, 17,
                        450L, "Falconsai/nsfw_image_detection", "nsfw-v1+harm-v2",
                        "Thumbnail or preview held for review"));
        List<List<String>> sent = framesSent();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1)).as("the cover's own bytes").containsExactlyInAnyOrder("AQID", "BAUG");
    }

    /** A clean cover changes nothing: the spread frames' scores are what the thresholds are tuned on. */
    @Test
    void moderate_whenTheThumbnailAndPreviewAreClean_theSpreadFramesVerdictAndScoresStand() throws Exception {
        givenTheBucketHolds(Map.of(
                "hls/vid123/source.mp4", PLAYBACK,
                "thumbnails/vid123.jpg", THUMBNAIL,
                "previews/vid123.webp", PREVIEW));
        givenFramesAreSampled(10);
        when(moderationClient.moderate(any())).thenReturn(SPREAD_APPROVED, new ModerationResponse(
                "APPROVED", "nsfw", 0.03, 0, 17, 450L, "Falconsai/nsfw_image_detection", "nsfw-v1+harm-v2"));

        VideoModerationCompletedEvent event = service(ENABLED).moderate("vid123", 60);

        assertThat(framesSent()).as("the cover was checked").hasSize(2);
        assertThat(event).usingRecursiveComparison().ignoringFields("eventId", "occurredAt")
                .isEqualTo(VideoModerationCompletedEvent.of("vid123", ModerationVerdict.APPROVED, "violence", 0.21, 0,
                        10, 700L, "openai/clip-vit-base-patch16", "nsfw-v1+harm-v2", null));
    }

    /**
     * ffmpeg may have built neither, and the transcode carries on without them. Nothing public is
     * nothing to check, not a failure. No empty call either: moderation-service answers no frames
     * with REVIEW.
     */
    @Test
    void moderate_whenThereIsNoThumbnailOrPreviewAnywhere_theSpreadFramesDecideAlone() throws Exception {
        givenTheBucketHolds(Map.of("hls/vid123/source.mp4", PLAYBACK));
        givenFramesAreSampled(10);
        when(moderationClient.moderate(any())).thenReturn(SPREAD_APPROVED);

        VideoModerationCompletedEvent event = service(ENABLED).moderate("vid123", 60);

        assertThat(framesSent()).hasSize(1);
        assertThat(event.verdict()).isEqualTo(ModerationVerdict.APPROVED);
        assertThat(event.maxScore()).isEqualTo(0.21);
        assertThat(event.reason()).isNull();
    }

    /**
     * A takedown during the transcode moved the thumbnail under quarantine/ with the rest, and it is
     * the cover the video gets back once the takedown is lifted. Here ffmpeg built no preview, and a
     * REVIEW on the cover holds as a REJECTED does.
     */
    @Test
    void moderate_checksAThumbnailThatIsAlreadyQuarantined() throws Exception {
        givenTheBucketHolds(Map.of(
                "quarantine/hls/vid123/source.mp4", PLAYBACK,
                "quarantine/thumbnails/vid123.jpg", THUMBNAIL));
        givenFramesAreSampled(10);
        when(moderationClient.moderate(any())).thenReturn(SPREAD_APPROVED, new ModerationResponse(
                "REVIEW", "nsfw", 0.66, 1, 1, 120L, "Falconsai/nsfw_image_detection", "nsfw-v1+harm-v2"));

        VideoModerationCompletedEvent event = service(ENABLED).moderate("vid123", 60);

        assertThat(framesSent()).hasSize(2).last().isEqualTo(List.of("AQID"));
        assertThat(event.verdict()).isEqualTo(ModerationVerdict.REVIEW);
        assertThat(event.maxScore()).isEqualTo(0.66);
    }

    @Test
    void moderateStory_imageSendsTheImageAsOneFrame() throws Exception {
        doAnswer(invocation -> {
            DownloadObjectArgs args = invocation.getArgument(0);
            Files.write(Path.of(args.filename()), new byte[]{1, 2, 3});
            return null;
        }).when(minioClient).downloadObject(any(DownloadObjectArgs.class));
        when(moderationClient.moderate(any())).thenReturn(new ModerationResponse(
                "APPROVED", "nsfw", 0.01, 0, 1, 40L, "m", "v1"));

        ModerationService.StoryVerdict verdict = service(ENABLED).moderateStory("s1", "stories/7/1.png", true);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<String>> frames = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(moderationClient).moderate(frames.capture());
        assertThat(frames.getValue()).containsExactly(java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}));
        assertThat(verdict.frames().verdict()).isEqualTo(ModerationVerdict.APPROVED);
        assertThat(verdict.durationSeconds()).isNull();
        verify(ffmpeg, never()).sampleFrames(any(), any(), anyInt(), anyInt());
        verify(videoProbe, never()).probe(any());
    }

    @Test
    void moderateStory_videoLongerThan60sIsRejectedWithoutClassifier() throws Exception {
        givenTheFileDownloads();
        when(videoProbe.probe(any())).thenReturn(new ProbedVideo(61, "h264", "aac", 720, 1280));

        ModerationService.StoryVerdict verdict = service(ENABLED).moderateStory("s1", "stories/7/1.mp4", false);

        assertThat(verdict.frames().verdict()).isEqualTo(ModerationVerdict.REJECTED);
        assertThat(verdict.frames().reason()).isEqualTo("Story video is longer than 60 seconds");
        assertThat(verdict.durationSeconds()).isEqualTo(61);
        verify(moderationClient, never()).moderate(any());
    }

    @Test
    void moderateStory_videoSamplesFramesAcrossItsLength() throws Exception {
        givenTheFileDownloads();
        when(videoProbe.probe(any())).thenReturn(new ProbedVideo(42, "h264", "aac", 720, 1280));
        givenFramesAreSampled(3);
        when(moderationClient.moderate(any())).thenReturn(new ModerationResponse(
                "APPROVED", "nsfw", 0.01, 0, 3, 40L, "m", "v1"));

        ModerationService.StoryVerdict verdict = service(ENABLED).moderateStory("s1", "stories/7/1.mp4", false);

        verify(ffmpeg).sampleFrames(any(), any(), org.mockito.ArgumentMatchers.eq(10), org.mockito.ArgumentMatchers.eq(42));
        assertThat(verdict.frames().verdict()).isEqualTo(ModerationVerdict.APPROVED);
        assertThat(verdict.durationSeconds()).isEqualTo(42);
    }

    @Test
    void moderateStory_classifierDownEndsInReview() throws Exception {
        givenTheFileDownloads();
        when(moderationClient.moderate(any())).thenThrow(new ModerationUnavailableException("connection refused"));

        ModerationService.StoryVerdict verdict = service(ENABLED).moderateStory("s1", "stories/7/1.jpg", true);

        verify(moderationClient, times(3)).moderate(any());
        assertThat(verdict.frames().verdict()).isEqualTo(ModerationVerdict.REVIEW);
        assertThat(verdict.frames().reason()).contains("3 attempts");
    }

    private ModerationServiceImpl service(ModerationProperties properties) {
        return new ModerationServiceImpl(minioClient, MINIO, properties, moderationClient, ffmpeg, videoProbe);
    }

    private void givenTheFileDownloads() throws Exception {
        doAnswer(invocation -> {
            DownloadObjectArgs args = invocation.getArgument(0);
            Files.writeString(Path.of(args.filename()), "not really an mp4");
            return null;
        }).when(minioClient).downloadObject(any(DownloadObjectArgs.class));
    }

    /** The bucket as MinIO answers a download: the keys given hold their bytes, any other is NoSuchKey. */
    private void givenTheBucketHolds(Map<String, byte[]> objects) throws Exception {
        doAnswer(invocation -> {
            DownloadObjectArgs args = invocation.getArgument(0);
            byte[] bytes = objects.get(args.object());
            if (bytes == null) {
                throw new ErrorResponseException(new ErrorResponse(
                        "NoSuchKey", "gone", "video-media", args.object(), null, null, null), null, null);
            }
            Files.write(Path.of(args.filename()), bytes);
            return null;
        }).when(minioClient).downloadObject(any(DownloadObjectArgs.class));
    }

    /** What each call to moderation-service carried, in order. */
    private List<List<String>> framesSent() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> frames = ArgumentCaptor.forClass(List.class);
        verify(moderationClient, atLeastOnce()).moderate(frames.capture());
        return frames.getAllValues();
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
