package com.tiktok.videoservice.service;

import com.tiktok.videoservice.dto.request.CreateVideoRequest;
import com.tiktok.videoservice.dto.request.UploadUrlRequest;
import com.tiktok.videoservice.dto.response.CursorPage;
import com.tiktok.videoservice.dto.response.UploadUrlResponse;
import com.tiktok.videoservice.dto.response.VideoResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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
     * @param cursor the previous page's {@code nextCursor}, or null to start at the newest video
     * @param size   null falls back to the configured default; above the configured maximum it is
     *               clamped down to it rather than refused
     */
    CursorPage<VideoResponse> getFeed(String cursor, Integer size);

    Page<VideoResponse> listByUser(Long requesterId, Long userId, Pageable pageable);

    void delete(Long requesterId, String videoId);
}
