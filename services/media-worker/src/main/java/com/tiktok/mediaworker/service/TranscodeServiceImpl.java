package com.tiktok.mediaworker.service;

import com.tiktok.mediaworker.config.MinioProperties;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
 * <p>ponytail: no thumbnail and no duration, because both need to decode the file. The client
 * falls back to its own poster and reads the length off the media element once it loads. Swap
 * this class for a real ffmpeg pipeline when adaptive bitrate or a real still frame matters —
 * nothing outside it knows how the artifacts are produced.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranscodeServiceImpl implements TranscodeService {

    /** Unknown until the file is decoded; the client reads the real length off the element. */
    private static final int UNKNOWN_DURATION_SECONDS = 0;

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    @Override
    @SneakyThrows
    public TranscodeResult transcode(String videoId, String rawFileUrl) {
        String bucket = minioProperties.bucket();
        String sourceKey = MediaKeys.objectKey(rawFileUrl, bucket).orElseThrow(() -> new IllegalArgumentException(
                "Raw upload %s of video %s is not in bucket %s".formatted(rawFileUrl, videoId, bucket)));
        String playbackKey = MediaKeys.playback(videoId);

        // Server-side copy: the bytes never leave MinIO, so a 2 GB upload costs one API call
        // rather than a download and an upload through this worker's heap.
        minioClient.copyObject(CopyObjectArgs.builder()
                .bucket(bucket)
                .object(playbackKey)
                .source(CopySource.builder().bucket(bucket).object(sourceKey).build())
                .build());

        log.info("Video {} is playable at {}", videoId, playbackKey);
        return new TranscodeResult(
                null, "%s/%s/%s".formatted(minioProperties.endpoint(), bucket, playbackKey), UNKNOWN_DURATION_SECONDS);
    }
}
