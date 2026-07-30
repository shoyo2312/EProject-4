package com.tiktok.mediaworker.event.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.video.VideoTranscodedEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VideoTranscodedEventProducer {

    private static final String TOPIC = "media.video-transcoded-events";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @SneakyThrows
    public void publish(VideoTranscodedEvent event) {
        kafkaTemplate.send(TOPIC, event.videoId(), objectMapper.writeValueAsString(event));
    }
}
