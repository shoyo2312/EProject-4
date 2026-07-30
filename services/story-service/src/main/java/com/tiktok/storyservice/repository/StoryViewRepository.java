package com.tiktok.storyservice.repository;

import com.tiktok.storyservice.entity.StoryView;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface StoryViewRepository extends MongoRepository<StoryView, String> {

    boolean existsByStoryIdAndViewerId(String storyId, Long viewerId);

    List<StoryView> findByStoryIdOrderByViewedAtDesc(String storyId);
}
