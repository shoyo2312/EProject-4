package com.tiktok.mediaworker.service;

import com.tiktok.mediaworker.config.MinioProperties;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Takes a video's transcoded media off the anonymous read path, and puts it back.
 *
 * <p>The bucket grants anonymous read on {@code hls/}, {@code thumbnails/} and {@code previews/}
 * because players follow those URLs without credentials (see BucketInitializer). A status in
 * video-service hides a rejected or taken-down video from every listing, but not from someone who
 * already has — or guesses — the URL. Moving the objects under {@code quarantine/}, which the policy
 * does not cover, is what actually stops them being served. They are moved, not deleted, because an
 * admin can overturn either decision.
 *
 * <p>A marker object records the state, so a transcode that finishes after a takedown can tell it
 * has to quarantine what it just wrote. Every operation is idempotent: events are redelivered, and a
 * takedown can arrive before there is any media to move.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaQuarantineService {

    static final String PREFIX = "quarantine/";

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    /** Marker first, then the move, so a transcode racing this sees the marker and re-runs it. */
    @SneakyThrows
    public void quarantine(String videoId) {
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(minioProperties.bucket())
                .object(MediaKeys.quarantineMarker(videoId))
                .stream(new ByteArrayInputStream(new byte[0]), 0, -1)
                .build());
        for (String key : publicKeys(videoId)) {
            move(key, PREFIX + key);
        }
        log.info("Quarantined the media of video {}", videoId);
    }

    @SneakyThrows
    public void release(String videoId) {
        for (String key : publicKeys(videoId)) {
            move(PREFIX + key, key);
        }
        minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(minioProperties.bucket())
                .object(MediaKeys.quarantineMarker(videoId))
                .build());
        log.info("Released the media of video {}", videoId);
    }

    @SneakyThrows
    public boolean isQuarantined(String videoId) {
        return exists(MediaKeys.quarantineMarker(videoId));
    }

    /**
     * Every key the transcode can have written for this video, whichever side of the quarantine it
     * is on now. The playlist and segments are listed rather than named, because only the
     * playback file's name is fixed.
     */
    private List<String> publicKeys(String videoId) throws Exception {
        List<String> keys = new ArrayList<>(List.of(MediaKeys.thumbnail(videoId), MediaKeys.preview(videoId)));
        String hls = MediaKeys.hlsPrefix(videoId);
        keys.addAll(list(hls));
        for (String key : list(PREFIX + hls)) {
            String original = key.substring(PREFIX.length());
            if (!keys.contains(original)) {
                keys.add(original);
            }
        }
        return keys;
    }

    private List<String> list(String prefix) throws Exception {
        List<String> keys = new ArrayList<>();
        for (Result<Item> item : minioClient.listObjects(ListObjectsArgs.builder()
                .bucket(minioProperties.bucket()).prefix(prefix).recursive(true).build())) {
            keys.add(item.get().objectName());
        }
        return keys;
    }

    /** Server-side copy then remove; a source that is not there is already where it should be. */
    private void move(String from, String to) throws Exception {
        if (!exists(from)) {
            return;
        }
        String bucket = minioProperties.bucket();
        minioClient.copyObject(CopyObjectArgs.builder()
                .bucket(bucket).object(to)
                .source(CopySource.builder().bucket(bucket).object(from).build())
                .build());
        minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(from).build());
    }

    private boolean exists(String key) throws Exception {
        try {
            minioClient.statObject(StatObjectArgs.builder().bucket(minioProperties.bucket()).object(key).build());
            return true;
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return false;
            }
            throw e;
        }
    }
}
