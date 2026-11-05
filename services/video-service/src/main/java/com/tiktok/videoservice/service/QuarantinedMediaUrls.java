package com.tiktok.videoservice.service;

import com.tiktok.videoservice.config.MinioProperties;
import com.tiktok.videoservice.dto.response.VideoResponse;
import com.tiktok.videoservice.entity.VideoStatus;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Points the admin console at the quarantined copy of a video's media.
 *
 * <p>media-worker moves a video's objects under {@code quarantine/}, outside the bucket's
 * anonymous-read policy, while it waits for its moderation verdict, when moderation holds it back
 * (REVIEW or REJECTED), when an admin takes it down, and when its owner deletes it. The URLs stored on the document stop resolving for
 * everyone. That is the point for viewers and a problem for the moderator who has to watch the
 * video to decide it, so the console, and only the console, gets short-lived signed URLs to the
 * quarantined copies.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuarantinedMediaUrls {

    /** Must match media-worker's MediaQuarantineService.PREFIX. */
    private static final String PREFIX = "quarantine/";

    /**
     * The statuses media-worker quarantines on. A deletion does too, whatever the status, and a
     * fresh transcode waits there for its verdict (PENDING_MODERATION).
     */
    private static final Set<VideoStatus> QUARANTINED = Set.of(VideoStatus.PENDING_MODERATION,
            VideoStatus.PENDING_REVIEW, VideoStatus.REJECTED, VideoStatus.TAKEN_DOWN);

    private static final Duration MEMO = Duration.ofSeconds(60);

    // ponytail: key -> expiry of positive stats, never swept — grows with the distinct quarantined
    // keys this instance has signed, which is a few bytes each. Swap for a bounded cache if it
    // ever shows up in heap.
    private final ConcurrentHashMap<String, Long> seen = new ConcurrentHashMap<>();

    /** In-cluster: the existence check runs once per quarantined row of a listing, then from memo. */
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    /** The signed URLs go to the moderator's browser, so they are signed for the public host. */
    @Qualifier("publicMinioClient")
    private final MinioClient publicMinioClient;

    public VideoResponse forAdmin(VideoResponse video) {
        if (!QUARANTINED.contains(video.status()) && video.deletedAt() == null) {
            return video;
        }
        return signedIfMoved(video, true);
    }

    /**
     * The uploader's own view of a video waiting for review. Its media stayed public while it
     * waited, so the owner could always watch it; now that it is quarantined they get the signed
     * copy the console gets. REJECTED and TAKEN_DOWN were removed: the owner keeps the tile, so
     * only the thumbnail is signed, and playback stays out of reach.
     */
    public VideoResponse forOwner(VideoResponse video) {
        return switch (video.status()) {
            case PENDING_MODERATION, PENDING_REVIEW -> signedIfMoved(video, true);
            case REJECTED, TAKEN_DOWN -> signedIfMoved(video, false);
            default -> video;
        };
    }

    private VideoResponse signedIfMoved(VideoResponse video, boolean playable) {
        // Only where this looks once media-worker has actually moved it: not yet, for a decision
        // it is still consuming, and never, for a video held for review or deleted before those
        // were quarantined. Those still play from where they are, and a signed URL to an empty
        // key would leave the moderator nothing to watch. The object checked is the one the
        // caller gets: playback for the console, the thumbnail for the owner's removed tile — a
        // video rejected before its transcode finished has a thumbnail and no HLS at all.
        String gate = key(playable ? video.hlsUrl() : video.thumbnailUrl());
        if (gate == null || !exists(PREFIX + gate)) {
            return video;
        }
        return new VideoResponse(
                video.id(), video.userId(), video.title(), video.description(),
                signed(video.thumbnailUrl()),
                playable ? signed(video.previewUrl()) : video.previewUrl(),
                playable ? signed(video.hlsUrl()) : video.hlsUrl(),
                video.durationSeconds(), video.width(), video.height(), video.status(), video.visibility(),
                video.viewCount(), video.likeCount(), video.commentCount(), video.commentsDisabled(),
                video.tags(), video.createdAt(), video.updatedAt(), video.publishedAt(), video.rawFileUrl(),
                video.failureReason(), video.takedownReason(), video.deletedAt(),
                video.purgeEventPublishedAt(), video.moderation(), video.accountCommentsOff(),
                video.ownerPrivate(), video.textModeration(), video.pinnedAt());
    }

    private String signed(String url) {
        String key = key(url);
        if (key == null) {
            return url;
        }
        try {
            return publicMinioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(minioProperties.bucket())
                    .object(PREFIX + key)
                    .expiry((int) minioProperties.urlExpiry().toSeconds())
                    .build());
        } catch (Exception e) {
            log.warn("Could not sign the quarantined copy of {}", key, e);
            return url;
        }
    }

    /** The URL is {@code {endpoint}/{bucket}/{key}}, as media-worker writes it; null for anything else. */
    private String key(String url) {
        if (url == null) {
            return null;
        }
        String marker = "/" + minioProperties.bucket() + "/";
        int at = url.indexOf(marker);
        return at < 0 ? null : url.substring(at + marker.length());
    }

    /**
     * A quarantined object stays put, so a positive answer is remembered for a minute: a Studio
     * page of fifty removed videos is otherwise fifty blocking stats. A miss is not remembered: it
     * flips to a hit the moment media-worker's move lands, and the next read should see that.
     */
    private boolean exists(String key) {
        Long until = seen.get(key);
        if (until != null && until > System.currentTimeMillis()) {
            return true;
        }
        try {
            minioClient.statObject(StatObjectArgs.builder().bucket(minioProperties.bucket()).object(key).build());
            seen.put(key, System.currentTimeMillis() + MEMO.toMillis());
            return true;
        } catch (ErrorResponseException e) {
            if (!"NoSuchKey".equals(e.errorResponse().code())) {
                log.warn("Could not check for the quarantined copy {}", key, e);
            }
            return false;
        } catch (Exception e) {
            log.warn("Could not check for the quarantined copy {}", key, e);
            return false;
        }
    }
}
