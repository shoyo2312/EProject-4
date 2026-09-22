package com.tiktok.videoservice.dto.response;

import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;

import java.time.Instant;
import java.util.List;

public record VideoResponse(
        String id,
        Long userId,
        String title,
        String description,
        String thumbnailUrl,
        /** Animated preview to play under the cursor; null means fall back to {@code thumbnailUrl}. */
        String previewUrl,
        String hlsUrl,
        Integer durationSeconds,
        /**
         * Display size of the playback file, rotation applied — lay the player out to this ratio.
         * Null while the video is still transcoding, when the file could not be measured, and for
         * videos uploaded before this was recorded; fall back to a default ratio then rather than
         * assuming portrait, which crops every landscape video.
         */
        Integer width,
        Integer height,
        VideoStatus status,
        VideoVisibility visibility,
        long viewCount,
        long likeCount,
        /** Null when {@code commentsDisabled} — a video with comments off exposes no comment total. */
        Long commentCount,
        boolean commentsDisabled,
        List<String> tags,
        Instant createdAt,
        /** Last write of any kind to the document. */
        Instant updatedAt,
        /** First time the video went live; null if it never has. See {@code Video.publishedAt}. */
        Instant publishedAt,
        /** The raw upload's object path in MinIO. Admin reads only — {@code null} on public ones. */
        String rawFileUrl,
        /** Why the transcode failed; null unless {@code status == FAILED}. */
        String failureReason,
        /** Why moderation removed it; null unless {@code status == TAKEN_DOWN}. */
        String takedownReason,
        /**
         * When the owner deleted the video. Admin reads only — a deleted video never reaches a
         * public read path, so this is null everywhere else.
         */
        Instant deletedAt,
        /**
         * When the VideoPurgedEvent for this row was acknowledged by the broker — the point after
         * which the media is gone from MinIO and the console can no longer play it. Admin reads
         * only; null for a live video and for a deleted one still inside its trash window — see
         * {@code Video.purgeEventPublishedAt}.
         */
        Instant purgeEventPublishedAt,
        /** What the classifier scored. Null on every public read path — admin console only. */
        ModerationResponse moderation
) {
}
