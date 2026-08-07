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

    List<Video> findTop100ByEventPublishedAtIsNullOrderByCreatedAtAsc();
}
