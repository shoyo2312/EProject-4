package com.tiktok.videoservice.repository;

import com.tiktok.videoservice.entity.ProcessedEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ProcessedEventRepository extends MongoRepository<ProcessedEvent, String> {

    boolean existsByEventId(String eventId);
}
