package com.tiktok.videoservice.service;

import com.tiktok.videoservice.dto.response.VideoResponse;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.exception.VideoNotFoundException;
import com.tiktok.videoservice.mapper.VideoMapper;
import com.tiktok.videoservice.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * The admin console's video listing.
 *
 * <p>It lives here rather than in admin-service because this is the database that owns
 * {@code status} and {@code visibility}; admin-service is not allowed to read another service's
 * store, and proxying the same query over HTTP would buy nothing but a second failure mode.
 * admin-service still owns the write side — a takedown is an audit row plus a Kafka event, which
 * this service consumes.
 */
@Service
@RequiredArgsConstructor
public class AdminVideoDirectory {

    private final VideoRepository videoRepository;
    private final VideoMapper videoMapper;
    private final QuarantinedMediaUrls quarantinedMediaUrls;

    public Page<VideoResponse> search(String query, VideoStatus status, Pageable pageable) {
        String term = (query == null || query.isBlank()) ? null : query.trim();
        return videoRepository.findForAdmin(status, term, pageable)
                .map(videoMapper::toAdminResponse)
                .map(quarantinedMediaUrls::forAdmin);
    }

    /**
     * One video by id, with no visibility rule applied at all — deliberately unlike
     * {@code VideoService.getById}, which hides anything not PUBLISHED and PUBLIC.
     *
     * <p>That rule is exactly wrong for moderation: the videos a moderator most needs to read back
     * are the ones nobody else can see — taken down, still processing, private, or deleted by their
     * owner after being reported. The listing hides owner-deleted videos too, so this is the only
     * route that answers for one; {@code deletedAt} on the response is what says so.
     */
    public VideoResponse getById(String videoId) {
        return videoRepository.findById(videoId)
                .map(videoMapper::toAdminResponse)
                .map(quarantinedMediaUrls::forAdmin)
                .orElseThrow(() -> new VideoNotFoundException(videoId));
    }
}
