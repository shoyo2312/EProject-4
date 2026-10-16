package com.tiktok.interactionservice.event.consumer;

import com.tiktok.interactionservice.entity.UserBan;
import com.tiktok.interactionservice.service.CommentService;
import com.tiktok.interactionservice.service.UserBanRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Routing by the eventType header — the payloads alone overlap — to the comment service or the ban record. */
class AdminModerationEventConsumerTest {

    private final CommentService commentService = mock(CommentService.class);
    private final UserBanRegistry userBanRegistry = mock(UserBanRegistry.class);
    private final AdminModerationEventConsumer consumer = new AdminModerationEventConsumer(
            commentService, userBanRegistry, JsonMapper.builder().findAndAddModules().build());

    private static byte[] header(String eventType) {
        return eventType.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aBanWithAnEnd_isRecordedUntilThen_asOfWhenItWasDecided() {
        consumer.onMessage("""
                {"eventId": "e1", "occurredAt": "2026-10-14T08:00:00Z", "userId": 7, "adminId": 1,
                 "reason": "spam", "bannedUntil": "2026-10-21T08:00:00Z"}
                """, header("UserBannedEvent"));

        verify(userBanRegistry).record(7L, Instant.parse("2026-10-21T08:00:00Z"), Instant.parse("2026-10-14T08:00:00Z"));
    }

    /** No end is what a permanent ban has always meant, events from before temporary bans included. */
    @Test
    void aBanWithoutAnEnd_isPermanent() {
        consumer.onMessage("""
                {"eventId": "e2", "occurredAt": "2026-10-14T08:00:00Z", "userId": 7, "adminId": 1, "reason": "spam"}
                """, header("UserBannedEvent"));

        verify(userBanRegistry).record(7L, UserBan.PERMANENT, Instant.parse("2026-10-14T08:00:00Z"));
    }

    @Test
    void anUnban_clearsTheRecord() {
        consumer.onMessage("""
                {"eventId": "e3", "occurredAt": "2026-10-15T08:00:00Z", "userId": 7, "adminId": 1, "reason": "appeal"}
                """, header("UserUnbannedEvent"));

        verify(userBanRegistry).record(7L, null, Instant.parse("2026-10-15T08:00:00Z"));
    }

    @Test
    void aCommentRemoval_goesToTheCommentService() {
        consumer.onMessage("""
                {"eventId": "e4", "occurredAt": "2026-10-15T08:00:00Z", "videoId": 100, "commentId": 5, "adminId": 1}
                """, header("CommentRemovedEvent"));

        verify(commentService).removeByAdmin(100L, 5L);
        verifyNoInteractions(userBanRegistry);
    }

    @Test
    void otherServicesEvents_andHeaderlessOnes_areLeftAlone() {
        consumer.onMessage("{\"videoId\": \"abc\"}", header("VideoTakenDownEvent"));
        consumer.onMessage("{\"userId\": 7}", null);

        verify(commentService, never()).removeByAdmin(anyLong(), anyLong());
        verify(userBanRegistry, never()).record(anyLong(), any(), any());
    }
}
