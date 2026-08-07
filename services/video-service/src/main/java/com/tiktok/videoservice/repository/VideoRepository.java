package com.tiktok.videoservice.repository;

import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface VideoRepository extends MongoRepository<Video, String>, VideoRepositoryCustom {

    Optional<Video> findByIdAndDeletedAtIsNull(String id);

    Page<Video> findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<Video> findByStatusAndVisibilityAndDeletedAtIsNullOrderByCreatedAtDesc(
            VideoStatus status, VideoVisibility visibility, Pageable pageable);

    /**
     * Outbox poll. Excludes soft-deleted videos: the poll runs every five seconds, so a video
     * deleted inside that window would otherwise still be announced to the rest of the system
     * and transcoded after its owner removed it.
     */
    List<Video> findTop100ByEventPublishedAtIsNullAndDeletedAtIsNullOrderByCreatedAtAsc();
}
