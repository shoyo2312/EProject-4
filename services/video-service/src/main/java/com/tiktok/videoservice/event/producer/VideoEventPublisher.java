package com.tiktok.videoservice.event.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.video.VideoPublishedEvent;
import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Polls videos whose VideoPublishedEvent hasn't been sent yet and forwards them to Kafka.
 * See {@link Video} for why this uses a per-document flag instead of a separate outbox
 * collection: this Mongo deployment has no replica set, so no multi-document transactions.
 *
 * Send is fire-and-forget (matches every other OutboxPublisher in this repo) — markEventPublished
 * happens right after the send call, not after broker ack. A failed send is only surfaced via the
 * logged callback below, not retried automatically.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoEventPublisher {

    private static final String TOPIC = "video.video-events";

    private final VideoRepository videoRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 5000)
    @SneakyThrows
    public void publishPending() {
        List<Video> pending = videoRepository.findTop100ByEventPublishedAtIsNullOrderByCreatedAtAsc();

        for (Video video : pending) {
            VideoPublishedEvent event = VideoPublishedEvent.of(
                    video.getId(), video.getUserId(), video.getTitle(), video.getRawFileUrl());

            kafkaTemplate.send(TOPIC, video.getId(), objectMapper.writeValueAsString(event))
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish VideoPublishedEvent for videoId={}", video.getId(), ex);
                        }
                    });
            video.markEventPublished();
            videoRepository.save(video);
        }
    }
}
