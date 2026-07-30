package com.tiktok.videoservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.video.VideoTranscodedEvent;
import com.tiktok.videoservice.entity.ProcessedEvent;
import com.tiktok.videoservice.repository.ProcessedEventRepository;
import com.tiktok.videoservice.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

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
            return;
        }

        videoRepository.findById(event.videoId()).ifPresent(video -> {
            if (event.success()) {
                video.markPublished(event.thumbnailUrl(), event.hlsUrl(), event.durationSeconds());
            } else {
                video.markFailed();
            }
            videoRepository.save(video);
        });

        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(event.eventId())
                .eventType(event.getClass().getSimpleName())
                .build());
    }
}
