package com.tiktok.videoservice.repository;

import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface VideoRepository extends MongoRepository<Video, String>, VideoRepositoryCustom {

    Optional<Video> findByIdAndDeletedAtIsNull(String id);

    /**
     * Batch counterpart of the single lookup above, for hydrating a list of ids the caller already
     * has — recommendation-service's feed returns ids and nothing else. Ids that do not resolve are
     * simply absent from the result: a feed that named a video deleted a moment ago is the normal
     * case, not an error.
     */
    List<Video> findByIdInAndDeletedAtIsNull(Collection<String> ids);

    /** Owner's own listing: everything they uploaded, whatever state it is in. */
    Page<Video> findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * Someone else's listing. Carries the same status+visibility filter the feed applies in
     * {@link VideoRepositoryCustom#findFeedPage}, because this endpoint is reachable without a
     * token: without it, a stranger reading
     * /api/v1/videos/users/{userId} gets the owner's PRIVATE uploads, their videos still
     * PROCESSING, and the ones moderation took down.
     */
    Page<Video> findByUserIdAndStatusAndVisibilityAndDeletedAtIsNullOrderByCreatedAtDesc(
            Long userId, VideoStatus status, VideoVisibility visibility, Pageable pageable);

    /**
     * Stranger listing for a viewer who is a confirmed friend of the owner: PUBLIC plus FRIENDS.
     * Same index ({@code user_videos_idx}) as the single-visibility query above — an {@code $in}
     * over two enum values still takes its bounds and its sort from the index.
     */
    Page<Video> findByUserIdAndStatusAndVisibilityInAndDeletedAtIsNullOrderByCreatedAtDesc(
            Long userId, VideoStatus status, Collection<VideoVisibility> visibilities, Pageable pageable);

    /**
     * Outbox poll. Excludes soft-deleted videos: the poll runs every five seconds, so a video
     * deleted inside that window would otherwise still be announced to the rest of the system
     * and transcoded after its owner removed it.
     *
     * <p>Also excludes rows whose event could not be built — see {@link Video#markEventFailed} for
     * why one such row would otherwise stall every video queued behind it.
     */
    List<Video> findTop100ByEventPublishedAtIsNullAndEventFailedAtIsNullAndDeletedAtIsNullOrderByCreatedAtAsc();

    /**
     * Deletion outbox poll: videos removed by their owner whose VideoDeletedEvent has not gone
     * out yet. The published-events poll above cannot serve this — it excludes exactly these rows.
     *
     * <p>Every soft-deleted row, including one deleted before its publication was ever announced:
     * that video's raw upload is in MinIO and this event is the only thing that still names the
     * key. See {@code VideoEventPublisher#publishPendingDeletions}.
     */
    List<Video> findTop100ByDeletedAtIsNotNullAndDeleteEventPublishedAtIsNullOrderByDeletedAtAsc();
}
