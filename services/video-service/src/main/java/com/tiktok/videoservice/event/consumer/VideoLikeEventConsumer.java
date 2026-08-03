package com.tiktok.videoservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.interaction.VideoLikeEvent;
import com.tiktok.videoservice.entity.ProcessedEvent;
import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * likeCount is updated with an atomic $inc rather than the usual load-mutate-save, since likes
 * happen at much higher frequency than other Video mutations and a read-modify-write would
 * either lose updates under concurrent likes or thrash against the @Version optimistic lock.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoLikeEventConsumer {

    private final ProcessedEventRepository processedEventRepository;
    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "interaction.like-events", groupId = "video-service")
    @SneakyThrows
    public void onMessage(String payload) {
        VideoLikeEvent event = objectMapper.readValue(payload, VideoLikeEvent.class);

        if (processedEventRepository.existsByEventId(event.eventId())) {
            return;
        }

        long delta = event.liked() ? 1 : -1;
        var result = mongoTemplate.updateFirst(
                Query.query(where("_id").is(String.valueOf(event.videoId()))),
                new Update().inc("likeCount", delta),
                Video.class);

        if (result.getMatchedCount() == 0) {
            log.warn("VideoLikeEvent for unknown videoId={}", event.videoId());
        }

        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(event.eventId())
                .eventType(event.getClass().getSimpleName())
                .build());
    }
}
