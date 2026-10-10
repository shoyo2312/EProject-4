package com.tiktok.chatservice.realtime;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoStatsFanoutTest {

    private static final String CREATED = """
            {"eventId":"e1","occurredAt":"2026-09-23T10:00:00Z","commentId":"9","videoId":"100",
             "userId":7,"content":"hello"}""";
    private static final String LIKED = """
            {"eventId":"e2","occurredAt":"2026-09-23T10:00:00Z","commentId":"9","videoId":"100",
             "userId":8,"liked":true,"likeCount":3}""";

    private DirtyVideoRegistry registry;
    private SimpMessagingTemplate messaging;
    private VideoAudienceClient audience;
    private VideoStatsFanout fanout;

    @BeforeEach
    void setUp() {
        registry = mock(DirtyVideoRegistry.class);
        messaging = mock(SimpMessagingTemplate.class);
        audience = mock(VideoAudienceClient.class);
        fanout = new VideoStatsFanout(registry, messaging, new ObjectMapper(), audience);
    }

    private static byte[] header(String eventType) {
        return eventType.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aCommentOnAPublicVideoGoesOut() {
        when(audience.commentsArePublic("100")).thenReturn(true);

        fanout.onCommentEvent(CREATED, header("CommentCreatedEvent"));

        verify(messaging).convertAndSend(eq("/topic/videos.100.comments"), any(CommentFrame.class));
    }

    /**
     * The topic is open to any socket, so a comment on a video anonymous viewers cannot see — or
     * whose comments are off — must not reach it. The count still moves: that number is public.
     */
    @Test
    void aCommentOnAVideoNotEveryoneMaySeeIsHeldBackButStillCounted() {
        when(audience.commentsArePublic("100")).thenReturn(false);

        fanout.onCommentEvent(CREATED, header("CommentCreatedEvent"));

        verify(messaging, never()).convertAndSend(anyString(), any(Object.class));
        verify(registry).markDirty("100");
    }

    @Test
    void aDeletionOnAVideoNotEveryoneMaySeeIsHeldBack() {
        when(audience.commentsArePublic("100")).thenReturn(false);

        fanout.onCommentEvent(CREATED, header("CommentDeletedEvent"));

        verify(messaging, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void aCommentLikeOnAVideoNotEveryoneMaySeeIsHeldBack() {
        when(audience.commentsArePublic("100")).thenReturn(false);

        fanout.onCommentLikeEvent(LIKED);

        verify(messaging, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void aCommentLikeOnAPublicVideoGoesOut() {
        when(audience.commentsArePublic("100")).thenReturn(true);

        fanout.onCommentLikeEvent(LIKED);

        verify(messaging).convertAndSend(eq("/topic/videos.100.comments"), any(CommentFrame.class));
    }
}
