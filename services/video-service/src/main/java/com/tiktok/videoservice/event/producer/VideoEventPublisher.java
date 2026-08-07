package com.tiktok.videoservice.event.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.video.VideoPublishedEvent;
import com.tiktok.kafka.outbox.OutboxDispatcher;
import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;
import java.util.List;

/**
 * Polls videos whose VideoPublishedEvent hasn't been sent yet and forwards them to Kafka.
 * See {@link Video} for why this uses a per-document flag instead of a separate outbox
 * collection: this Mongo deployment has no replica set, so no multi-document transactions.
 *
 * <p>Marking is delegated to {@link OutboxDispatcher} so a video is only marked published once
 * the broker acknowledges it — see that class for why doing it inline loses events.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoEventPublisher {

    private static final String TOPIC = "video.video-events";

    private final VideoRepository videoRepository;
    private final OutboxDispatcher outboxDispatcher;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 5000)
    public void publishPending() {
        List<Video> pending = videoRepository.findTop100ByEventPublishedAtIsNullOrderByCreatedAtAsc();
        if (pending.isEmpty()) {
            return;
        }

        int published = outboxDispatcher.dispatch(pending, this::toRecord, this::markPublished);

        if (published < pending.size()) {
            log.warn("Published {}/{} video events, the rest stay pending for the next poll",
                    published, pending.size());
        }
    }

    private ProducerRecord<String, String> toRecord(Video video) {
        VideoPublishedEvent event = VideoPublishedEvent.of(
                video.getId(), video.getUserId(), video.getTitle(), video.getRawFileUrl());
        try {
            return new ProducerRecord<>(TOPIC, video.getId(), objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException ex) {
            // Unchecked so the dispatcher can skip this one row and still send the rest.
            throw new UncheckedIOException(ex);
        }
    }

    private void markPublished(Video video) {
        video.markEventPublished();
        videoRepository.updateEventPublished(video);
    }
}
