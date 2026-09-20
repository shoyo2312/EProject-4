package com.tiktok.notificationservice.repository;

import com.tiktok.notificationservice.entity.ProcessedEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ProcessedEventRepository
        extends MongoRepository<ProcessedEvent, String>, ProcessedEventRepositoryCustom {

    boolean existsByEventId(String eventId);
}
