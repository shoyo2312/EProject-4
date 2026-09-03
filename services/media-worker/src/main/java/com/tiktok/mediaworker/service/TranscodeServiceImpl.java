package com.tiktok.mediaworker.service;

import com.tiktok.mediaworker.config.MediaVideoProperties;
import com.tiktok.mediaworker.config.MinioProperties;
import io.minio.DownloadObjectArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.UploadObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Turns an upload into the two objects the client needs: a playback file a player can start on
 * the first bytes of, and a still frame to show before it does.
 *
 * <p>Neither step re-encodes. The playback file is the upload remuxed with its moov atom moved to
 * the front ({@code -movflags +faststart}) — same streams, same bytes of video, only the index
 * relocated — and the thumbnail is one decoded frame. That keeps the CPU cost of an upload flat
 * in its length rather than proportional to it, which is what would turn this listener into a
 * queue. Format normalisation, HLS and multi-bitrate variants are deliberately not here.
 *
 * <p>ponytail: the file is pulled through this worker's disk, where the previous copy-only
 * version never moved a byte out of MinIO. Moving the moov atom means rewriting the container, so
 * there is no server-side operation that does it. Budget roughly 2x the upload's size in scratch
 * space per concurrent transcode.
 *
 * <p>Both ffmpeg steps degrade rather than fail — see {@link Ffmpeg}. A video whose container the
 * mp4 muxer will not take is stored as it arrived, and a frame that will not decode leaves
 * {@code thumbnailUrl} null, which is the state the client already falls back on.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranscodeServiceImpl implements TranscodeService {

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final MediaVideoProperties videoLimits;
    private final VideoProbe videoProbe;
    private final Ffmpeg ffmpeg;

    @Override
    @SneakyThrows
    public TranscodeResult transcode(String videoId, String rawFileUrl) {
        String bucket = minioProperties.bucket();
        String sourceKey = MediaKeys.objectKey(rawFileUrl, bucket).orElseThrow(() -> new IllegalArgumentException(
                "Raw upload %s of video %s is not in bucket %s".formatted(rawFileUrl, videoId, bucket)));

        // Checked before the download so an oversized upload costs one HEAD, not its own size in
        // traffic and scratch space.
        long sizeBytes = minioClient.statObject(StatObjectArgs.builder()
                .bucket(bucket).object(sourceKey).build()).size();
        if (sizeBytes > videoLimits.maxBytes()) {
            throw new MediaRejectedException("Video is %s; the maximum is %s."
                    .formatted(humanBytes(sizeBytes), humanBytes(videoLimits.maxBytes())));
        }

        Path work = Files.createTempDirectory("transcode-" + videoId + "-");
        try {
            Path source = work.resolve("source");
            minioClient.downloadObject(DownloadObjectArgs.builder()
                    .bucket(bucket).object(sourceKey).filename(source.toString()).build());

            int durationSeconds = videoProbe.durationSeconds(source.toUri().toString());
            if (durationSeconds > videoLimits.maxDurationSeconds()) {
                throw new MediaRejectedException("Video is %s; the maximum is %s."
                        .formatted(humanDuration(durationSeconds), humanDuration(videoLimits.maxDurationSeconds())));
            }

            String playbackKey = MediaKeys.playback(videoId);
            Path playback = work.resolve("playback.mp4");
            if (!ffmpeg.faststart(source, playback)) {
                log.info("Video {} could not be remuxed to mp4, storing the upload unchanged", videoId);
                playback = source;
            }
            minioClient.uploadObject(UploadObjectArgs.builder()
                    .bucket(bucket).object(playbackKey)
                    .filename(playback.toString())
                    .contentType("video/mp4")
                    .build());

            String thumbnailUrl = null;
            Path thumbnail = work.resolve("thumbnail.jpg");
            if (ffmpeg.stillFrame(source, thumbnail, thumbnailSecond(durationSeconds))) {
                String thumbnailKey = MediaKeys.thumbnail(videoId);
                minioClient.uploadObject(UploadObjectArgs.builder()
                        .bucket(bucket).object(thumbnailKey)
                        .filename(thumbnail.toString())
                        .contentType("image/jpeg")
                        .build());
                thumbnailUrl = url(bucket, thumbnailKey);
            } else {
                log.info("Video {} yielded no still frame, leaving it without a thumbnail", videoId);
            }

            log.info("Video {} is playable at {} ({}s)", videoId, playbackKey, durationSeconds);
            return new TranscodeResult(thumbnailUrl, url(bucket, playbackKey), durationSeconds);
        } finally {
            deleteRecursively(work);
        }
    }

    /**
     * One second in, so the frame is past the fade-in and black leader most clips open on, but
     * never past the halfway mark of a clip too short to have one.
     */
    private static int thumbnailSecond(int durationSeconds) {
        return Math.min(1, durationSeconds / 2);
    }

    private String url(String bucket, String key) {
        return "%s/%s/%s".formatted(minioProperties.endpoint(), bucket, key);
    }

    /**
     * Scratch space is the one resource a failed transcode can leak, and the listener retries
     * three times before giving up — so this runs on the way out of every path, successful or not.
     */
    private static void deleteRecursively(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Could not delete {}", path, e);
                }
            });
        } catch (IOException e) {
            log.warn("Could not clean up {}", directory, e);
        }
    }

    private static String humanBytes(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        return mb >= 1024 ? "%.2f GB".formatted(mb / 1024) : "%.0f MB".formatted(mb);
    }

    private static String humanDuration(int seconds) {
        return "%dm%02ds".formatted(seconds / 60, seconds % 60);
    }
}
