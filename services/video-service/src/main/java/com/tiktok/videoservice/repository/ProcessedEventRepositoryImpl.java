package com.tiktok.videoservice.repository;

import com.tiktok.videoservice.entity.ProcessedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import static org.springframework.data.mongodb.core.query.Criteria.where;

@RequiredArgsConstructor
public class ProcessedEventRepositoryImpl implements ProcessedEventRepositoryCustom {

    private final MongoTemplate mongoTemplate;

    @Override
    public boolean tryClaim(String eventId, String eventType) {
        try {
            mongoTemplate.insert(ProcessedEvent.builder()
                    .eventId(eventId)
                    .eventType(eventType)
                    .build());
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    @Override
    public void releaseClaim(String eventId) {
        mongoTemplate.remove(Query.query(where("eventId").is(eventId)), ProcessedEvent.class);
    }
}
