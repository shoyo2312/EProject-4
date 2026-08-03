package com.tiktok.videoservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.video.VideoTranscodedEvent;
import com.tiktok.videoservice.entity.ProcessedEvent;
import com.tiktok.videoservice.repository.ProcessedEventRepository;
import com.tiktok.videoservice.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class VideoTranscodedEventConsumer {

    private final ProcessedEventRepository processedEventRepository;
    private final VideoRepository videoRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "media.video-transcoded-events", groupId = "video-service")
    @SneakyThrows
    public void onMessage(String payload) {
        VideoTranscodedEvent event = objectMapper.readValue(payload, VideoTranscodedEvent.class);

        if (processedEventRepository.existsByEventId(event.eventId())) {
            log.debug("Skipping already-processed VideoTranscodedEvent eventId={}", event.eventId());
            return;
        }

        if (!event.success()) {
            log.warn("VideoTranscodedEvent failure for videoId={}: {}", event.videoId(), event.failureReason());
        }

        videoRepository.findById(event.videoId()).ifPresentOrElse(video -> {
            if (event.success()) {
                video.markPublished(event.thumbnailUrl(), event.hlsUrl(), event.durationSeconds());
            } else {
                video.markFailed();
            }
            videoRepository.save(video);
        }, () -> log.warn("VideoTranscodedEvent for unknown videoId={}", event.videoId()));

        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(event.eventId())
                .eventType(event.getClass().getSimpleName())
                .build());
    }
}
