package com.tiktok.chatservice.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Every topic whose records mean "a counter on this profile moved" — see {@code VideoStatsFanout}.
 * Neither record is read for its numbers: the flusher re-reads current counts from user-service /
 * video-service, this only marks which ids to re-read.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserStatsFanout {

    private final DirtyUserRegistry registry;
    private final ObjectMapper objectMapper;

    /**
     * Both sides of the edge moved: the follower's followingCount and the target's
     * followerCount.
     */
    @KafkaListener(topics = "user.follow-events")
    public void onFollowEvent(String payload) {
        JsonNode node = parse(payload);
        if (node == null) {
            return;
        }
        registry.markDirty(text(node, "followerId"));
        registry.markDirty(text(node, "followingId"));
    }

    @KafkaListener(topics = "video.like-owner-events")
    public void onLikeOwnerEvent(String payload) {
        JsonNode node = parse(payload);
        if (node != null) {
            registry.markDirty(text(node, "ownerId"));
        }
    }

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
