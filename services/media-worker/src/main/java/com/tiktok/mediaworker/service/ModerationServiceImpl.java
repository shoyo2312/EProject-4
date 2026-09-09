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
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Samples frames from the playback file and asks moderation-service about them.
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

    @Override
    public VideoModerationCompletedEvent moderate(String videoId, Integer durationSeconds) {
        if (!moderationProperties.enabled()) {
            // An operator switched it off; approving is the only honest answer, and saying so in
            // the reason keeps it out of the pile of videos a model actually looked at.
            return VideoModerationCompletedEvent.of(videoId, ModerationVerdict.APPROVED, null, 0.0, 0, 0,
                    0L, null, null, "Automatic moderation is disabled");
        }

        int attempts = Math.max(1, moderationProperties.attempts());
        String lastFailure = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return score(videoId, durationSeconds);
            } catch (ModerationUnavailableException e) {
                lastFailure = e.getMessage();
                log.warn("Moderation of video {} failed on attempt {}/{}: {}",
                        videoId, attempt, attempts, e.getMessage());
                if (attempt < attempts) {
                    pause();
                }
            }
        }

        log.error("Moderation of video {} failed {} times, sending it to the review queue", videoId, attempts);
        return VideoModerationCompletedEvent.unavailable(videoId,
                "Automatic moderation could not be completed after %d attempts (%s)".formatted(attempts, lastFailure));
    }

    private VideoModerationCompletedEvent score(String videoId, Integer durationSeconds) {
        Path work = createWorkDirectory(videoId);
        try {
            Path source = work.resolve("playback.mp4");
            minioClient.downloadObject(DownloadObjectArgs.builder()
                    .bucket(minioProperties.bucket())
                    .object(MediaKeys.playback(videoId))
                    .filename(source.toString())
                    .build());

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

            return VideoModerationCompletedEvent.of(videoId, verdictOf(response), response.label(),
                    response.maxScore(), response.suspiciousFrames(), response.totalFrames(),
                    response.processingTimeMs(), response.model(), response.modelVersion(), null);
        } catch (Exception e) {
            throw new ModerationUnavailableException(
                    "Could not moderate video %s: %s".formatted(videoId, e.getMessage()), e);
        } finally {
            deleteRecursively(work);
        }
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
