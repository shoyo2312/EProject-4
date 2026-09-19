package com.tiktok.mediaworker.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.admin.VideoRestoredEvent;
import com.tiktok.event.admin.VideoTakenDownEvent;
import com.tiktok.mediaworker.service.MediaQuarantineService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Makes an admin's takedown real for the media itself: video-service stops listing the video, and
 * this stops MinIO serving it to anyone who already has the URL. A restore puts it back.
 *
 * <p>Routed on the eventType header — admin.moderation-events carries several types whose JSON
 * cannot be told apart. No inbox: both operations are idempotent moves, and the topic is keyed by
 * target so a takedown and its restore arrive in order.
 *
 * <p>ponytail: a restore always releases, even when the video returns to a status that is itself
 * hidden (REJECTED before the takedown). The listings still hide it; only a guessed URL would
 * play. Track the pre-takedown verdict here if that ever matters.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminModerationEventConsumer {

    private static final String VIDEO_TAKEN_DOWN = "VideoTakenDownEvent";
    private static final String VIDEO_RESTORED = "VideoRestoredEvent";

    private final MediaQuarantineService quarantine;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "admin.moderation-events", groupId = "media-worker")
    @SneakyThrows
    public void onMessage(String payload,
                          @Header(name = "eventType", required = false) byte[] eventTypeHeader) {
        String eventType = eventTypeHeader == null ? null : new String(eventTypeHeader, StandardCharsets.UTF_8);
        if (VIDEO_TAKEN_DOWN.equals(eventType)) {
            quarantine.quarantine(objectMapper.readValue(payload, VideoTakenDownEvent.class).videoId());
        } else if (VIDEO_RESTORED.equals(eventType)) {
            quarantine.release(objectMapper.readValue(payload, VideoRestoredEvent.class).videoId());
        } else {
            log.debug("Ignoring moderation eventType={}", eventType);
        }
    }
}
