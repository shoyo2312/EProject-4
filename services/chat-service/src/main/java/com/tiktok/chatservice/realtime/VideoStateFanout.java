package com.tiktok.chatservice.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Publication, deletion, visibility and moderation. Sent the moment they arrive rather than
 * coalesced: each happens once in a video's life, and a video that has just been taken down has to
 * leave the screen now, not at the end of the window.
 *
 * <p>The frame is a hint, not the truth. VideoRestoredEvent does not carry the status the video is
 * being restored to — video-service restores it to whatever it was before the takedown — so the
 * client refetches the video on any state frame instead of trusting the value.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoStateFanout {

    private final SimpMessagingTemplate messaging;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "video.video-events")
    public void onVideoEvent(String payload,
                             @Header(name = "eventType", required = false) byte[] eventTypeHeader) {
        JsonNode node = parse(payload);
        if (node == null) {
            return;
        }
        String videoId = text(node, "videoId");
        if (videoId == null) {
            return;
        }

        // A missing header means VideoPublishedEvent: producers older than the mixed topic sent
        // only that type.
        String eventType = eventTypeHeader == null
                ? "VideoPublishedEvent"
                : new String(eventTypeHeader);

        VideoFrame frame = switch (eventType) {
            case "VideoPublishedEvent" -> VideoFrame.status(videoId, "PUBLISHED");
            // A videoId nothing here has ever heard of is normal: a video deleted before its
            // publication was announced still emits this. Publishing to a destination with no
            // subscribers is a no-op, which is exactly the required no-op.
            case "VideoDeletedEvent" -> VideoFrame.status(videoId, "DELETED");
            case "VideoVisibilityChangedEvent" -> VideoFrame.visibility(videoId, text(node, "visibility"));
            default -> null;
        };
        publish(videoId, frame, eventType);
    }

    @KafkaListener(topics = "admin.moderation-events")
    public void onModerationEvent(String payload,
                                  @Header(name = "eventType", required = false) byte[] eventTypeHeader) {
        if (eventTypeHeader == null) {
            // Other services warn about this; it is not this listener's job to dead-letter another
            // service's producer bug.
            log.debug("Moderation event without an eventType header, dropped");
            return;
        }
        JsonNode node = parse(payload);
        if (node == null) {
            return;
        }
        String videoId = text(node, "videoId");
        if (videoId == null) {
            // UserBannedEvent and friends share this topic and have no videoId.
            return;
        }

        String eventType = new String(eventTypeHeader);
        VideoFrame frame = switch (eventType) {
            case "VideoTakenDownEvent" -> VideoFrame.status(videoId, "TAKEN_DOWN");
            case "VideoRestoredEvent" -> VideoFrame.status(videoId, "PUBLISHED");
            default -> null;
        };
        publish(videoId, frame, eventType);
    }

    private void publish(String videoId, VideoFrame frame, String eventType) {
        if (frame == null) {
            log.debug("State eventType={} not forwarded", eventType);
            return;
        }
        messaging.convertAndSend(VideoTopics.video(videoId), frame);
    }

    private JsonNode parse(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            log.warn("Unreadable state event dropped: {}", e.getMessage());
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
