package com.tiktok.chatservice.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class VideoStateFanoutTest {

    private SimpMessagingTemplate messaging;
    private VideoStateFanout fanout;

    @BeforeEach
    void setUp() {
        messaging = mock(SimpMessagingTemplate.class);
        fanout = new VideoStateFanout(messaging, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void aVisibilityChangeIsForwardedAsAStateFrame() {
        String payload = """
                {"eventId":"e1","occurredAt":"2026-09-09T10:00:00Z","videoId":"100",
                 "userId":7,"visibility":"PRIVATE"}""";

        fanout.onVideoEvent(payload, "VideoVisibilityChangedEvent".getBytes(StandardCharsets.UTF_8));

        verify(messaging).convertAndSend(eq("/topic/videos.100"), any(VideoFrame.class));
    }

    @Test
    void aMissingHeaderIsTreatedAsAPublication() {
        String payload = """
                {"eventId":"e2","occurredAt":"2026-09-09T10:00:00Z","videoId":"101","userId":7}""";

        fanout.onVideoEvent(payload, null);

        verify(messaging).convertAndSend(eq("/topic/videos.101"), any(VideoFrame.class));
    }

    @Test
    void anApprovedVerdictIsBroadcastToTheFeed() {
        fanout.onModerationVerdict("""
                {"eventId":"e3","videoId":"103","verdict":"APPROVED"}""");

        verify(messaging).convertAndSend(eq("/topic/feed"), any(VideoFrame.class));
    }

    @Test
    void aVerdictThatIsNotApprovedStaysOffTheFeed() {
        fanout.onModerationVerdict("""
                {"eventId":"e4","videoId":"104","verdict":"REVIEW"}""");

        verify(messaging, never()).convertAndSend(any(String.class), any(VideoFrame.class));
    }

    @Test
    void anUnknownEventTypeIsIgnoredWithoutThrowing() {
        fanout.onVideoEvent("{\"videoId\":\"102\"}",
                "SomethingElseEvent".getBytes(StandardCharsets.UTF_8));

        verify(messaging, never()).convertAndSend(any(String.class), any(VideoFrame.class));
    }

    @Test
    void malformedJsonIsIgnoredWithoutThrowing() {
        fanout.onVideoEvent("not json", "VideoPublishedEvent".getBytes(StandardCharsets.UTF_8));

        verify(messaging, never()).convertAndSend(any(String.class), any(VideoFrame.class));
    }

    @Test
    void aTakedownIsForwardedFromTheModerationTopic() {
        String payload = """
                {"eventId":"e3","occurredAt":"2026-09-09T10:00:00Z","videoId":"103",
                 "adminId":1,"reason":"nudity"}""";

        fanout.onModerationEvent(payload, "VideoTakenDownEvent".getBytes(StandardCharsets.UTF_8));

        verify(messaging).convertAndSend(eq("/topic/videos.103"), any(VideoFrame.class));
    }

    @Test
    void aModerationEventWithoutAVideoIdIsIgnored() {
        String payload = """
                {"eventId":"e4","occurredAt":"2026-09-09T10:00:00Z","userId":9,"adminId":1}""";

        fanout.onModerationEvent(payload, "UserBannedEvent".getBytes(StandardCharsets.UTF_8));

        verify(messaging, never()).convertAndSend(any(String.class), any(VideoFrame.class));
    }
}
