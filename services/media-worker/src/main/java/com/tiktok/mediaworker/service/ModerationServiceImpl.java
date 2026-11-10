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
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.List;
import java.util.stream.Stream;

/**
 * Samples frames from the playback file and asks moderation-service about them, then asks again
 * about the published thumbnail and preview (see {@code withCover}).
 *
 * <p>It reads the playback object rather than the raw upload on purpose: that file is already
 * normalized to something ffmpeg decodes cheaply and predictably, and it is the file viewers
 * would actually see. Re-downloading it after the transcode already had the bytes on disk is the
 * price of keeping moderation on its own event — the alternative was folding the verdict into
 * VideoTranscodedEvent, which would mean a video could not be recorded as transcoded until the
 * classifier had also answered.
 *
 * <p>The retry wraps the download and the sampling as well as the call, not just the call. All
 * three are infrastructure that has nothing to say about the content, and a moment's trouble in
 * any of them should not cost a clean video a trip through the admin queue.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModerationServiceImpl implements ModerationService {

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final ModerationProperties moderationProperties;
    private final ModerationClient moderationClient;
    private final Ffmpeg ffmpeg;
    private final VideoProbe videoProbe;

    /** A story is a short clip; anything longer is refused rather than screened. */
    static final int MAX_STORY_SECONDS = 60;

    @Override
    public VideoModerationCompletedEvent moderate(String videoId, Integer durationSeconds) {
        if (!moderationProperties.enabled()) {
            // An operator switched it off; approving is the only honest answer, and saying so in
            // the reason keeps it out of the pile of videos a model actually looked at.
            return VideoModerationCompletedEvent.of(videoId, ModerationVerdict.APPROVED, null, 0.0, 0, 0,
                    0L, null, null, "Automatic moderation is disabled");
        }

        return withRetries("video " + videoId, videoId, () -> score(videoId, durationSeconds));
    }

    @Override
    public StoryVerdict moderateStory(String storyId, String mediaKey, boolean image) {
        AtomicReference<Integer> duration = new AtomicReference<>();
        if (!moderationProperties.enabled()) {
            return new StoryVerdict(VideoModerationCompletedEvent.of(storyId, ModerationVerdict.APPROVED, null,
                    0.0, 0, 0, 0L, null, null, "Automatic moderation is disabled"),
                    image ? null : probedLength(storyId, mediaKey));
        }
        VideoModerationCompletedEvent frames = withRetries("story " + storyId, storyId,
                () -> scoreStory(storyId, mediaKey, image, duration));
        return new StoryVerdict(frames, duration.get());
    }

    /**
     * The retry wraps the download and the sampling as well as the call: all of it is
     * infrastructure that has nothing to say about the content.
     */
    private VideoModerationCompletedEvent withRetries(String subject, String id,
                                                      Supplier<VideoModerationCompletedEvent> attempt) {
        int attempts = Math.max(1, moderationProperties.attempts());
        String lastFailure = null;

        for (int attemptNo = 1; attemptNo <= attempts; attemptNo++) {
            try {
                return attempt.get();
            } catch (ModerationUnavailableException e) {
                lastFailure = e.getMessage();
                log.warn("Moderation of {} failed on attempt {}/{}: {}", subject, attemptNo, attempts, e.getMessage());
                if (attemptNo < attempts) {
                    pause();
                }
            }
        }

        log.error("Moderation of {} failed {} times, holding it for review", subject, attempts);
        return VideoModerationCompletedEvent.unavailable(id,
                "Automatic moderation could not be completed after %d attempts (%s)".formatted(attempts, lastFailure));
    }

    private VideoModerationCompletedEvent scoreStory(String storyId, String mediaKey, boolean image,
                                                     AtomicReference<Integer> duration) {
        Path work = createWorkDirectory(storyId);
        try {
            Path source = work.resolve("story" + mediaKey.substring(mediaKey.lastIndexOf('.')));
            download(mediaKey, source);

            List<String> frames;
            if (image) {
                frames = encode(List.of(source));
            } else {
                int seconds = videoProbe.probe(source.toUri().toString()).durationSeconds();
                duration.set(seconds);
                if (seconds > MAX_STORY_SECONDS) {
                    return VideoModerationCompletedEvent.of(storyId, ModerationVerdict.REJECTED, null, 0.0, 0, 0, 0L,
                            null, null, "Story video is longer than %d seconds".formatted(MAX_STORY_SECONDS));
                }
                List<Path> sampled = ffmpeg.sampleFrames(source, Files.createDirectory(work.resolve("frames")),
                        moderationProperties.frameCount(), seconds);
                if (sampled.isEmpty()) {
                    return VideoModerationCompletedEvent.unavailable(storyId, "No frames could be sampled from the story");
                }
                frames = encode(sampled);
            }

            ModerationResponse response = moderationClient.moderate(frames);
            log.info("Story {} moderated as {} ({} {} over {} frames) in {}ms", storyId, response.verdict(),
                    response.label(), response.maxScore(), response.totalFrames(), response.processingTimeMs());
            return VideoModerationCompletedEvent.of(storyId, verdictOf(response), response.label(),
                    response.maxScore(), response.suspiciousFrames(), response.totalFrames(),
                    response.processingTimeMs(), response.model(), response.modelVersion(), null);
        } catch (Exception e) {
            throw new ModerationUnavailableException(
                    "Could not moderate story %s: %s".formatted(storyId, e.getMessage()), e);
        } finally {
            deleteRecursively(work);
        }
    }

    /** Only for the duration while moderation is off; the story is approved either way. */
    private Integer probedLength(String storyId, String mediaKey) {
        Path work = createWorkDirectory(storyId);
        try {
            Path source = work.resolve("story" + mediaKey.substring(mediaKey.lastIndexOf('.')));
            download(mediaKey, source);
            return videoProbe.probe(source.toUri().toString()).durationSeconds();
        } catch (Exception e) {
            log.warn("Could not read the length of story {}", storyId, e);
            return null;
        } finally {
            deleteRecursively(work);
        }
    }

    private VideoModerationCompletedEvent score(String videoId, Integer durationSeconds) {
        Path work = createWorkDirectory(videoId);
        try {
            Path source = work.resolve("playback.mp4");
            downloadEitherSide(MediaKeys.playback(videoId), source);

            Path frameDir = Files.createDirectory(work.resolve("frames"));
            List<Path> frames = ffmpeg.sampleFrames(source, frameDir,
                    moderationProperties.frameCount(), durationSeconds == null ? 1 : durationSeconds);

            if (frames.isEmpty()) {
                // Not retried and not approved. A playable file that yields no decodable frame is
                // odd enough to be worth a human, and trying again produces the same nothing.
                log.warn("No frames could be sampled from video {}, sending it to the review queue", videoId);
                return VideoModerationCompletedEvent.unavailable(videoId,
                        "No frames could be sampled from the video");
            }

            ModerationResponse response = moderationClient.moderate(encode(frames));
            log.info("Video {} moderated as {} ({} {} over {} frames, {} suspicious) in {}ms",
                    videoId, response.verdict(), response.label(), response.maxScore(),
                    response.totalFrames(), response.suspiciousFrames(), response.processingTimeMs());

            VideoModerationCompletedEvent spread = VideoModerationCompletedEvent.of(videoId, verdictOf(response),
                    response.label(), response.maxScore(), response.suspiciousFrames(), response.totalFrames(),
                    response.processingTimeMs(), response.model(), response.modelVersion(), null);
            // Already held or removed: the cover can only hold, so asking would change nothing but the scores.
            return spread.verdict() == ModerationVerdict.APPROVED ? withCover(videoId, work, spread) : spread;
        } catch (Exception e) {
            throw new ModerationUnavailableException(
                    "Could not moderate video %s: %s".formatted(videoId, e.getMessage()), e);
        } finally {
            deleteRecursively(work);
        }
    }

    /**
     * The thumbnail and the preview, which every feed card, profile grid and search hit shows. Both
     * come from the opening seconds, which the spread frames of a longer video step over, so a video
     * explicit only there was approved with an explicit cover.
     *
     * <p>A call of their own, and one that can only hold the video. The preview is near-identical
     * frames of one moment: in with the spread frames they would be the "second frame that agrees"
     * the reject and harm rules wait for, and one odd scene would remove a video. A cover that is
     * not APPROVED therefore sends the video to a person, carrying the cover's scores so the admin
     * sees what held it. A clean cover leaves the spread verdict untouched, scores included, since
     * those are what the thresholds are tuned against.
     *
     * <p>Either may be missing: ffmpeg could not build it, and the transcode carries on without it.
     * ponytail: a restore that moves one out of quarantine between its two reads also reads as
     * missing; pass the transcode's thumbnailUrl/previewUrl in if that race ever matters.
     */
    private VideoModerationCompletedEvent withCover(String videoId, Path work, VideoModerationCompletedEvent spread)
            throws Exception {
        List<Path> cover = new java.util.ArrayList<>();
        for (String key : List.of(MediaKeys.thumbnail(videoId), MediaKeys.preview(videoId))) {
            Path target = work.resolve(key.substring(key.lastIndexOf('/') + 1));
            if (downloadIfProduced(key, target)) {
                cover.add(target);
            }
        }
        if (cover.isEmpty()) {
            return spread;
        }

        ModerationResponse response = moderationClient.moderate(encode(cover));
        log.info("Cover of video {} moderated as {} ({} {} over {} frames) in {}ms", videoId, response.verdict(),
                response.label(), response.maxScore(), response.totalFrames(), response.processingTimeMs());
        if (verdictOf(response) == ModerationVerdict.APPROVED) {
            return spread;
        }
        return VideoModerationCompletedEvent.of(videoId, ModerationVerdict.REVIEW, response.label(),
                response.maxScore(), response.suspiciousFrames(), response.totalFrames(),
                response.processingTimeMs(), response.model(), response.modelVersion(),
                "Thumbnail or preview held for review");
    }

    /**
     * An object of the video from wherever it is now. A takedown or deletion that lands while the
     * video is transcoding has already moved the fresh output under {@code quarantine/} by the time
     * this runs (see {@code VideoEventConsumer}), and reading only the public key of the playback
     * file failed every attempt: a REVIEW verdict with no scores, and a video later restored from
     * the takedown landed in the review queue instead of being published. The quarantined copy is
     * the same bytes.
     *
     * <p>A move in the other direction between the two reads fails this attempt, and the retry
     * around it finds the file where it settled.
     */
    private void downloadEitherSide(String key, Path target) throws Exception {
        try {
            download(key, target);
        } catch (ErrorResponseException e) {
            if (!"NoSuchKey".equals(e.errorResponse().code())) {
                throw e;
            }
            download(MediaQuarantineService.PREFIX + key, target);
        }
    }

    /** @return false when the object is on neither side of the quarantine */
    private boolean downloadIfProduced(String key, Path target) throws Exception {
        try {
            downloadEitherSide(key, target);
            return true;
        } catch (ErrorResponseException e) {
            if (!"NoSuchKey".equals(e.errorResponse().code())) {
                throw e;
            }
            return false;
        }
    }

    private void download(String key, Path target) throws Exception {
        minioClient.downloadObject(DownloadObjectArgs.builder()
                .bucket(minioProperties.bucket())
                .object(key)
                .filename(target.toString())
                .build());
    }

    /**
     * An unrecognised verdict string is treated as REVIEW rather than as an error. The two
     * services deploy separately, so a moderation-service that has learned a new verdict this
     * build does not understand must not end with the video published by default.
     */
    private static ModerationVerdict verdictOf(ModerationResponse response) {
        try {
            return ModerationVerdict.valueOf(response.verdict());
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("Unknown moderation verdict {}, treating it as REVIEW", response.verdict());
            return ModerationVerdict.REVIEW;
        }
    }

    @SneakyThrows
    private static List<String> encode(List<Path> frames) {
        Base64.Encoder encoder = Base64.getEncoder();
        List<String> encoded = new java.util.ArrayList<>(frames.size());
        for (Path frame : frames) {
            encoded.add(encoder.encodeToString(Files.readAllBytes(frame)));
        }
        return encoded;
    }

    @SneakyThrows
    private static Path createWorkDirectory(String videoId) {
        return Files.createTempDirectory("moderate-" + videoId + "-");
    }

    private void pause() {
        try {
            Thread.sleep(moderationProperties.retryBackoffMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted between moderation attempts", e);
        }
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Could not delete {}", path, e);
                }
            });
        } catch (IOException e) {
            log.warn("Could not clean up {}", root, e);
        }
    }
}
