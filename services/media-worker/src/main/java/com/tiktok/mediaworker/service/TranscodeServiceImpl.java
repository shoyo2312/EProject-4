package com.tiktok.mediaworker.service;

import com.tiktok.mediaworker.config.MediaVideoProperties;
import com.tiktok.mediaworker.config.MinioProperties;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Stands in for a transcode pipeline without one: the uploaded file is copied, server-side, to a
 * deterministic playback key and served as-is. No ffmpeg, no variants, no segmenting — what the
 * uploader sent is what viewers get.
 *
 * <p>This replaces an earlier stub that wrote an empty {@code master.m3u8} and a text file named
 * {@code .jpg}. Both were valid objects at the right keys, so every read path reported success
 * while the player had nothing to play and every thumbnail was a broken image. Copying the real
 * file is barely more code and makes an upload actually watchable.
 *
 * <p>The copy lands under {@link MediaKeys#hlsPrefix} so deletion keeps working unchanged — that
 * prefix is listed recursively on cleanup — and the URL is a plain {@code .mp4}, which the web
 * client plays through the {@code <video>} element directly instead of hls.js.
 *
 * <p>ponytail: no thumbnail, because that needs a decoded still frame. The client falls back to
 * its own poster. Swap this class for a real ffmpeg pipeline when adaptive bitrate or a real
 * still frame matters — nothing outside it knows how the artifacts are produced.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranscodeServiceImpl implements TranscodeService {

    private static final int PROBE_URL_EXPIRY_SECONDS = 300;

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final MediaVideoProperties videoLimits;
    private final VideoProbe videoProbe;

    @Override
    @SneakyThrows
    public TranscodeResult transcode(String videoId, String rawFileUrl) {
        String bucket = minioProperties.bucket();
        String sourceKey = MediaKeys.objectKey(rawFileUrl, bucket).orElseThrow(() -> new IllegalArgumentException(
                "Raw upload %s of video %s is not in bucket %s".formatted(rawFileUrl, videoId, bucket)));
        String playbackKey = MediaKeys.playback(videoId);

        long sizeBytes = minioClient.statObject(StatObjectArgs.builder()
                .bucket(bucket).object(sourceKey).build()).size();
        if (sizeBytes > videoLimits.maxBytes()) {
            throw new MediaRejectedException("Video is %s; the maximum is %s."
                    .formatted(humanBytes(sizeBytes), humanBytes(videoLimits.maxBytes())));
        }

        String probeUrl = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(bucket)
                .object(sourceKey)
                .expiry(PROBE_URL_EXPIRY_SECONDS, TimeUnit.SECONDS)
                .build());
        int durationSeconds = videoProbe.durationSeconds(probeUrl);
        if (durationSeconds > videoLimits.maxDurationSeconds()) {
            throw new MediaRejectedException("Video is %s; the maximum is %s."
                    .formatted(humanDuration(durationSeconds), humanDuration(videoLimits.maxDurationSeconds())));
        }

        // Server-side copy: the bytes never leave MinIO, so a 2 GB upload costs one API call
        // rather than a download and an upload through this worker's heap.
        minioClient.copyObject(CopyObjectArgs.builder()
                .bucket(bucket)
                .object(playbackKey)
                .source(CopySource.builder().bucket(bucket).object(sourceKey).build())
                .build());

        log.info("Video {} is playable at {} ({}s)", videoId, playbackKey, durationSeconds);
        return new TranscodeResult(
                null, "%s/%s/%s".formatted(minioProperties.endpoint(), bucket, playbackKey), durationSeconds);
    }

    private static String humanBytes(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        return mb >= 1024 ? "%.2f GB".formatted(mb / 1024) : "%.0f MB".formatted(mb);
    }

    private static String humanDuration(int seconds) {
        return "%dm%02ds".formatted(seconds / 60, seconds % 60);
    }
}
