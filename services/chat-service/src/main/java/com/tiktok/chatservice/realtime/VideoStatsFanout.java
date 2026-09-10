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
 * Every topic whose records mean "a counter on this video moved". None of them is read for its
 * contents beyond the videoId: the numbers come from interaction-service at flush time, so this
 * listener never has to know which counter changed or by how much, and a new counter needs no
 * change here.
 *
 * <p>The comment topic is the exception — it also forwards the comment itself, because a comment
 * is content and there is no counter to read it back from.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoStatsFanout {

    private final DirtyVideoRegistry registry;
    private final SimpMessagingTemplate messaging;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = {
            "interaction.like-events",
            "interaction.share-events",
            "interaction.save-events",
            "interaction.view-events"
    })
    public void onCounterEvent(String payload) {
        JsonNode node = parse(payload);
        if (node != null) {
            registry.markDirty(text(node, "videoId"));
        }
    }

    @KafkaListener(topics = "interaction.comment-events")
    public void onCommentEvent(String payload,
                               @Header(name = "eventType", required = false) byte[] eventTypeHeader) {
        JsonNode node = parse(payload);
        if (node == null) {
            return;
        }
        String videoId = text(node, "videoId");
        if (videoId == null) {
            return;
        }
        registry.markDirty(videoId);

        // interaction.comment-events carries two shapes. Routing is on the header, never on the
        // payload: Jackson would happily read a deletion as a creation with every missing field
        // null, and nothing would log an error.
        String eventType = eventTypeHeader == null ? "" : new String(eventTypeHeader);
        CommentFrame frame = switch (eventType) {
            case "CommentCreatedEvent" -> CommentFrame.created(videoId, text(node, "commentId"),
                    text(node, "userId"), text(node, "content"), text(node, "occurredAt"));
            case "CommentDeletedEvent" -> CommentFrame.deleted(videoId, text(node, "commentId"));
            default -> null;
        };
        if (frame == null) {
            log.debug("Comment event with eventType={} not forwarded", eventType);
            return;
        }
        messaging.convertAndSend(VideoTopics.comments(videoId), frame);
    }

    /**
     * Returns null instead of throwing. A record this listener cannot read is not worth three
     * retries and a dead-letter entry — realtime is a layer on top of REST, and dropping one frame
     * costs a stale render until the next event, while a poisoned partition would stop every
     * video's updates on this instance.
     */
    private JsonNode parse(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            log.warn("Unreadable realtime event dropped: {}", e.getMessage());
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
