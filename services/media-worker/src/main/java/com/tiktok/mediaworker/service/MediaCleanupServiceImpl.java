package com.tiktok.mediaworker.service;

import com.tiktok.mediaworker.config.MinioProperties;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Deleting a video's document removes it from every read path, but not from the bucket. Nothing
 * else ever refers to those objects again, so without this they are storage that only grows —
 * and the transcode output in particular has no lifecycle rule to fall back on, because the
 * abandoned-upload rule only covers the {@code raw/} prefix.
 *
 * <p>This runs in media-worker rather than in video-service because MinIO credentials live here
 * and nowhere else, and because the layout of the derived objects is this service's own — see
 * {@link MediaKeys}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaCleanupServiceImpl implements MediaCleanupService {

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    @Override
    public void deleteMediaFor(String videoId, String rawFileUrl) {
        List<String> keys = new ArrayList<>();
        keys.add(MediaKeys.thumbnail(videoId));
        keys.add(MediaKeys.preview(videoId));
        keys.addAll(listUnder(MediaKeys.hlsPrefix(videoId)));
        // Skipped rather than guessed when the URL does not name this bucket — see
        // MediaKeys.objectKey. The abandoned-upload lifecycle rule is what covers it then.
        MediaKeys.objectKey(rawFileUrl, minioProperties.bucket()).ifPresentOrElse(
                keys::add,
                () -> log.warn("Raw upload {} of video {} is not in bucket {}, leaving it in place",
                        rawFileUrl, videoId, minioProperties.bucket()));

        for (String key : keys) {
            remove(key);
        }
    }

    /**
     * A real transcode writes one playlist plus a segment per few seconds of video, so the HLS
     * output is a directory and not a file. Listed rather than assumed, since only the playlist
     * key is deterministic.
     */
    private List<String> listUnder(String prefix) {
        List<String> keys = new ArrayList<>();
        try {
            Iterable<Result<Item>> items = minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(minioProperties.bucket())
                    .prefix(prefix)
                    .recursive(true)
                    .build());
            for (Result<Item> item : items) {
                keys.add(item.get().objectName());
            }
        } catch (Exception e) {
            log.error("Could not list {} for cleanup, its objects stay behind", prefix, e);
        }
        return keys;
    }

    /**
     * Logged and swallowed per object rather than thrown. The alternative is kafka-lib retrying
     * the whole deletion three times and then parking it in the DLT, which costs the removals
     * that did succeed and gains nothing: an object left in the bucket is a bill, not a bug the
     * rest of the system can see.
     */
    private void remove(String key) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(minioProperties.bucket())
                    .object(key)
                    .build());
        } catch (Exception e) {
            log.error("Could not delete {} from {}", key, minioProperties.bucket(), e);
        }
    }
}
