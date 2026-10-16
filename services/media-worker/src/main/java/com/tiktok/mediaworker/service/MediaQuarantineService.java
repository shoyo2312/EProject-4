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
 * video-service hides a video from every listing, but not from someone who already has — or
 * guesses — the URL. Moving the objects under {@code quarantine/}, which the policy does not cover,
 * is what actually stops them being served. They are moved, not deleted, because an admin can
 * overturn the decision, and the admin console plays them from there through signed URLs.
 *
 * <p>Several decisions can each hold a video's media, and each is lifted on its own terms (see
 * {@link Hold}). The media goes back on the public path only once nothing holds it. A single
 * marker used to stand for every reason, so the restore that lifted a takedown also published the
 * media of a video still waiting for review, or of one its owner had deleted since.
 *
 * <p>One marker object per hold records them, so a transcode that finishes after a hold can tell
 * it has to quarantine what it just wrote. Every operation is idempotent: events are redelivered,
 * and a hold can arrive before there is any media to move.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaQuarantineService {

    static final String PREFIX = "quarantine/";

    /** Why a video's media is off the public path. */
    public enum Hold {
        /** An admin took the video down. Lifted by a restore. */
        TAKEN_DOWN,
        /** Automatic moderation did not approve it (REVIEW or REJECTED). Lifted by an admin's approval. */
        MODERATION,
        /** Its owner deleted it. Never lifted: the media waits out the trash window for the purge. */
        DELETED
    }

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    /** Marker first, then the move, so a transcode racing this sees the marker and re-runs it. */
    @SneakyThrows
    public void hold(String videoId, Hold hold) {
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(minioProperties.bucket())
                .object(MediaKeys.quarantineHold(videoId, hold.name()))
                .stream(new ByteArrayInputStream(new byte[0]), 0, -1)
                .build());
        moveIn(videoId);
        log.info("Quarantined the media of video {} ({})", videoId, hold);
    }

    /**
     * An admin restore, read the way video-service's {@code Video.markRestored} reads it: on a
     * taken-down video it lifts the takedown, on any other it is the admin approving what
     * moderation held back. A deletion is not something a restore can undo.
     */
    @SneakyThrows
    public void restore(String videoId) {
        boolean liftedTakedown = remove(MediaKeys.quarantineHold(videoId, Hold.TAKEN_DOWN.name()));
        // Written before holds were recorded, for a takedown or a REJECTED verdict; either way
        // the restore is what lifted it then.
        boolean liftedLegacy = remove(MediaKeys.quarantineMarker(videoId));
        if (!liftedTakedown && !liftedLegacy) {
            remove(MediaKeys.quarantineHold(videoId, Hold.MODERATION.name()));
        }

        if (isQuarantined(videoId)) {
            log.info("Video {} is restored but its media is still held", videoId);
            return;
        }
        moveOut(videoId);
        // A hold that landed while this was moving the media back moved what it found then, not
        // what this put back after it looked.
        if (isQuarantined(videoId)) {
            moveIn(videoId);
            return;
        }
        log.info("Released the media of video {}", videoId);
    }

    /** For a transcode that has just written its output: put it under quarantine if anything holds it. */
    @SneakyThrows
    public void enforce(String videoId) {
        if (isQuarantined(videoId)) {
            moveIn(videoId);
        }
    }

    @SneakyThrows
    public boolean isQuarantined(String videoId) {
        return exists(MediaKeys.quarantineMarker(videoId)) || !list(MediaKeys.quarantineHolds(videoId)).isEmpty();
    }

    private void moveIn(String videoId) throws Exception {
        for (String key : publicKeys(videoId)) {
            move(key, PREFIX + key);
        }
    }

    private void moveOut(String videoId) throws Exception {
        for (String key : publicKeys(videoId)) {
            move(PREFIX + key, key);
        }
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

    /** @return whether there was anything to remove */
    private boolean remove(String key) throws Exception {
        if (!exists(key)) {
            return false;
        }
        minioClient.removeObject(RemoveObjectArgs.builder().bucket(minioProperties.bucket()).object(key).build());
        return true;
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
