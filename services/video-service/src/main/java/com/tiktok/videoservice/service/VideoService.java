package com.tiktok.videoservice.service;

import com.tiktok.videoservice.dto.request.CreateVideoRequest;
import com.tiktok.videoservice.dto.request.UploadUrlRequest;
import com.tiktok.videoservice.dto.response.CursorPage;
import com.tiktok.videoservice.dto.response.UploadUrlResponse;
import com.tiktok.videoservice.dto.response.UserVideoStatsResponse;
import com.tiktok.videoservice.dto.response.VideoPolicyResponse;
import com.tiktok.videoservice.dto.response.VideoResponse;
import com.tiktok.videoservice.entity.VideoVisibility;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface VideoService {

    /**
     * Step one of publishing: hands the client a presigned PUT URL to upload the raw file with,
     * plus the {@code fileUrl} to pass to {@link #publish} once the upload finishes. Nothing is
     * persisted here — an abandoned upload leaves only an orphan object, not a half-made Video.
     */
    UploadUrlResponse createUploadUrl(Long userId, UploadUrlRequest request);

    VideoResponse publish(Long userId, CreateVideoRequest request);

    VideoResponse getById(Long requesterId, String videoId);

    /**
     * The same visibility rule as {@link #getById}, applied to a list, and answering in the order
     * asked. Ids that do not resolve — deleted, still processing, someone else's private video —
     * are dropped rather than reported, so the caller gets a shorter list instead of a failure.
     */
    List<VideoResponse> getByIds(Long requesterId, List<String> videoIds);

    /**
     * @param cursor the previous page's {@code nextCursor}, or null to start at the newest video
     * @param size   null falls back to the configured default; above the configured maximum it is
     *               clamped down to it rather than refused
     */
    CursorPage<VideoResponse> getFeed(String cursor, Integer size);

    Page<VideoResponse> listByUser(Long requesterId, Long userId, Pageable pageable);

    UserVideoStatsResponse getUserStats(Long requesterId, Long userId);

    /**
     * Owner and comment setting, with no visibility filtering — see {@link VideoPolicyResponse}
     * for why this is not a projection of {@link #getById}.
     */
    VideoPolicyResponse getPolicy(String videoId);

    void delete(Long requesterId, String videoId);

    /**
     * The owner switching their own video between PUBLIC and PRIVATE from the detail page.
     * Owner-only, same as {@link #delete}; returns the updated video.
     */
    VideoResponse updateVisibility(Long requesterId, String videoId, VideoVisibility visibility);

    /**
     * The owner turning new comments on or off for their own video. Owner-only, same as
     * {@link #updateVisibility}; returns the updated video.
     */
    VideoResponse updateCommentsDisabled(Long requesterId, String videoId, boolean disabled);
}
