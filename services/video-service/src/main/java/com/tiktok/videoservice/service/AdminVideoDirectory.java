package com.tiktok.videoservice.service;

import com.tiktok.videoservice.dto.response.DailyVideoStatsResponse;
import com.tiktok.videoservice.dto.response.VideoResponse;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.exception.VideoNotFoundException;
import com.tiktok.videoservice.mapper.VideoMapper;
import com.tiktok.videoservice.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;

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

    /**
     * @param ownerIds the owners a handle search resolved to — see
     *                 {@link com.tiktok.videoservice.repository.VideoRepositoryCustom#findForAdmin}
     *                 for why they widen the search rather than narrowing it
     */
    public Page<VideoResponse> search(String query, VideoStatus status, Collection<Long> ownerIds,
                                      Boolean deleted, Pageable pageable) {
        String term = (query == null || query.isBlank()) ? null : query.trim();
        return videoRepository.findForAdmin(status, term, ownerIds, deleted, pageable)
                .map(videoMapper::toAdminResponse)
                .map(quarantinedMediaUrls::forAdmin);
    }

    /**
     * Uploads per day over the last {@code days} days, with each day's cohort standing.
     *
     * <p>The window is measured back from now rather than from the console's "as of" date: the
     * console cuts the series itself, because it needs the period before the one on screen to
     * compare against and would otherwise have to ask twice.
     */
    public List<DailyVideoStatsResponse> dailyStats(int days) {
        return videoRepository.countDailyUploads(Instant.now().minus(days, ChronoUnit.DAYS));
    }

    /**
     * One video by id, with no visibility rule applied at all — deliberately unlike
     * {@code VideoService.getById}, which hides anything not PUBLISHED and PUBLIC.
     *
     * <p>That rule is exactly wrong for moderation: the videos a moderator most needs to read back
     * are the ones nobody else can see — taken down, still processing, private, or deleted by their
     * owner after being reported. The listing answers for a deleted video too now, but only when
     * asked to include it; this route always does — {@code deletedAt} on the response is what
     * tells a deleted one apart.
     */
    public VideoResponse getById(String videoId) {
        return videoRepository.findById(videoId)
                .map(videoMapper::toAdminResponse)
                .map(quarantinedMediaUrls::forAdmin)
                .orElseThrow(() -> new VideoNotFoundException(videoId));
    }
}
