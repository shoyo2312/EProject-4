package com.tiktok.videoservice.service;

import com.tiktok.videoservice.config.MinioProperties;
import com.tiktok.videoservice.dto.response.VideoResponse;
import com.tiktok.videoservice.entity.VideoStatus;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Points the admin console at the quarantined copy of a video's media.
 *
 * <p>media-worker moves a rejected or taken-down video's objects under {@code quarantine/}, outside
 * the bucket's anonymous-read policy, so the URLs stored on the document stop resolving for
 * everyone. That is the point for viewers and a problem for the moderator who has to watch the video
 * to decide whether to restore it — so the console, and only the console, gets short-lived signed
 * URLs to the quarantined copies.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuarantinedMediaUrls {

    /** Must match media-worker's MediaQuarantineService.PREFIX. */
    private static final String PREFIX = "quarantine/";

    /** The statuses media-worker quarantines on: its own REJECTED verdict and an admin takedown. */
    private static final Set<VideoStatus> QUARANTINED = Set.of(VideoStatus.REJECTED, VideoStatus.TAKEN_DOWN);

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    public VideoResponse forAdmin(VideoResponse video) {
        if (!QUARANTINED.contains(video.status())) {
            return video;
        }
        return new VideoResponse(
                video.id(), video.userId(), video.title(), video.description(),
                signed(video.thumbnailUrl()), signed(video.previewUrl()), signed(video.hlsUrl()),
                video.durationSeconds(), video.width(), video.height(), video.status(), video.visibility(),
                video.viewCount(), video.likeCount(), video.commentCount(), video.commentsDisabled(),
                video.tags(), video.createdAt(), video.updatedAt(), video.publishedAt(), video.rawFileUrl(),
                video.failureReason(), video.takedownReason(), video.deletedAt(), video.moderation());
    }

    /** The URL is {@code {endpoint}/{bucket}/{key}}, as media-worker writes it; anything else is left alone. */
    private String signed(String url) {
        if (url == null) {
            return null;
        }
        String marker = "/" + minioProperties.bucket() + "/";
        int at = url.indexOf(marker);
        if (at < 0) {
            return url;
        }
        String key = url.substring(at + marker.length());
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
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
}
