package com.tiktok.videoservice.service;

import com.tiktok.videoservice.dto.request.CreateVideoRequest;
import com.tiktok.videoservice.dto.response.CursorPage;
import com.tiktok.videoservice.dto.response.VideoResponse;
import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
import com.tiktok.videoservice.exception.NotVideoOwnerException;
import com.tiktok.videoservice.exception.VideoNotFoundException;
import com.tiktok.videoservice.mapper.VideoMapper;
import com.tiktok.videoservice.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.data.web.SpringDataWebProperties;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class VideoServiceImpl implements VideoService {

    private final VideoRepository videoRepository;
    private final VideoMapper videoMapper;
    private final SpringDataWebProperties pageableProperties;

    @Override
    public VideoResponse publish(Long userId, CreateVideoRequest request) {
        Video video = Video.builder()
                .id(Video.newId())
                .userId(userId)
                .title(request.title())
                .description(request.description())
                .rawFileUrl(request.rawFileUrl())
                .visibility(request.visibility())
                .status(VideoStatus.PROCESSING)
                .viewCount(0)
                .likeCount(0)
                .commentCount(0)
                .build();

        Video saved = videoRepository.save(video);
        return videoMapper.toResponse(saved);
    }

    @Override
    public VideoResponse getById(Long requesterId, String videoId) {
        Video video = videoRepository.findByIdAndDeletedAtIsNull(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));

        if (!isOwner(requesterId, video) && !isPubliclyVisible(video)) {
            throw new VideoNotFoundException(videoId);
        }

        return videoMapper.toResponse(video);
    }

    /**
     * Cursor-paged rather than offset-paged; see {@code VideoRepositoryCustom.findFeedPage} for
     * what offset paging cost here. {@link #listByUser} below keeps offsets on purpose — a profile
     * grid is finite, gets jumped around in, and its owner has a use for a total.
     */
    @Override
    public CursorPage<VideoResponse> getFeed(String cursor, Integer size) {
        FeedCursor after = FeedCursor.decode(cursor);
        int limit = clampSize(size);

        // One row past the page. Its presence is what says there is more to come — the same answer
        // a count query gives, for the cost of one extra document instead of a second scan.
        List<Video> rows = videoRepository.findFeedPage(
                after == null ? null : after.createdAt(),
                after == null ? null : after.id(),
                limit + 1);

        boolean hasMore = rows.size() > limit;
        List<Video> page = hasMore ? rows.subList(0, limit) : rows;

        // Null only once the feed is exhausted. A full page that happens to end on the very last
        // video still hands back a cursor, and the next request answers empty and settles it —
        // guessing otherwise would need the count this method exists to avoid.
        String nextCursor = hasMore ? FeedCursor.of(page.get(page.size() - 1)).encode() : null;

        return new CursorPage<>(page.stream().map(videoMapper::toResponse).toList(), nextCursor);
    }

    /**
     * The feed no longer goes through Spring Data's Pageable resolver, so the cap that
     * {@link com.tiktok.videoservice.config.PageableConfig} restores for the other endpoints does
     * not reach it. Same property applied by hand, rather than a second knob free to drift from it:
     * {@code ?size=100000} has to stay clamped whichever pagination style the endpoint uses.
     */
    private int clampSize(Integer size) {
        SpringDataWebProperties.Pageable limits = pageableProperties.getPageable();
        return size == null
                ? limits.getDefaultPageSize()
                : Math.clamp(size, 1, limits.getMaxPageSize());
    }

    /**
     * The owner sees their whole shelf; anyone else sees only what is actually published and
     * public — the same line {@link #getById} draws for a single video, applied to the list.
     * The endpoint takes no token, so requesterId is null for anonymous callers and they fall
     * to the filtered branch.
     */
    @Override
    public Page<VideoResponse> listByUser(Long requesterId, Long userId, Pageable pageable) {
        Page<Video> videos = isSelf(requesterId, userId)
                ? videoRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId, pageable)
                : videoRepository.findByUserIdAndStatusAndVisibilityAndDeletedAtIsNullOrderByCreatedAtDesc(
                        userId, VideoStatus.PUBLISHED, VideoVisibility.PUBLIC, pageable);

        return videos.map(videoMapper::toResponse);
    }

    @Override
    public void delete(Long requesterId, String videoId) {
        Video video = videoRepository.findByIdAndDeletedAtIsNull(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));

        if (!isOwner(requesterId, video)) {
            throw new NotVideoOwnerException(videoId);
        }

        video.markDeleted();
        videoRepository.updateSoftDeleted(video);
    }

    private boolean isOwner(Long requesterId, Video video) {
        return isSelf(requesterId, video.getUserId());
    }

    private boolean isSelf(Long requesterId, Long userId) {
        return requesterId != null && requesterId.equals(userId);
    }

    private boolean isPubliclyVisible(Video video) {
        return video.getVisibility() == VideoVisibility.PUBLIC && video.getStatus() == VideoStatus.PUBLISHED;
    }
}
