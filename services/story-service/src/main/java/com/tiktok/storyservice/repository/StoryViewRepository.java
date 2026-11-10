package com.tiktok.storyservice.repository;

import com.tiktok.storyservice.entity.StoryView;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface StoryViewRepository extends MongoRepository<StoryView, String>, StoryViewRepositoryCustom {

    List<StoryView> findByViewerIdAndStoryIdIn(Long viewerId, Collection<String> storyIds);

    List<StoryView> findByStoryIdAndViewedAtBeforeOrderByViewedAtDesc(String storyId, Instant before, Pageable page);
}
