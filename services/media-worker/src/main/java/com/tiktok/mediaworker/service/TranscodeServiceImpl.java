package com.tiktok.mediaworker.service;

import com.tiktok.mediaworker.config.MinioProperties;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Simulates the transcode/thumbnail pipeline: no ffmpeg call here, just placeholder
 * artifacts written to MinIO at deterministic keys so the event-driven wiring
 * (VideoPublishedEvent in, VideoTranscodedEvent out) can be exercised end-to-end.
 * A real implementation would download rawFileUrl and shell out to ffmpeg.
 */
@Service
@RequiredArgsConstructor
public class TranscodeServiceImpl implements TranscodeService {

    private static final int SIMULATED_DURATION_SECONDS = 30;
    private static final String THUMBNAIL_CONTENT_TYPE = "image/jpeg";
    private static final String HLS_CONTENT_TYPE = "application/vnd.apple.mpegurl";

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    @Override
    @SneakyThrows
    public TranscodeResult transcode(String videoId, String rawFileUrl) {
        String thumbnailKey = "thumbnails/%s.jpg".formatted(videoId);
        String hlsKey = "hls/%s/master.m3u8".formatted(videoId);

        putPlaceholder(thumbnailKey, "placeholder-thumbnail".getBytes(StandardCharsets.UTF_8), THUMBNAIL_CONTENT_TYPE);
        putPlaceholder(hlsKey, "#EXTM3U\n#EXT-X-ENDLIST\n".getBytes(StandardCharsets.UTF_8), HLS_CONTENT_TYPE);

        String base = minioProperties.endpoint() + "/" + minioProperties.bucket();
        return new TranscodeResult(base + "/" + thumbnailKey, base + "/" + hlsKey, SIMULATED_DURATION_SECONDS);
    }

    private void putPlaceholder(String key, byte[] content, String contentType) throws Exception {
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(minioProperties.bucket())
                .object(key)
                .stream(new ByteArrayInputStream(content), content.length, -1)
                .contentType(contentType)
                .build());
    }
}
