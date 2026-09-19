package com.tiktok.mediaworker.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tiktok.event.admin.UserBannedEvent;
import com.tiktok.event.admin.VideoRestoredEvent;
import com.tiktok.event.admin.VideoTakenDownEvent;
import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.event.video.VideoModerationCompletedEvent;
import com.tiktok.event.video.VideoTranscodedEvent;
import com.tiktok.mediaworker.event.producer.VideoModerationEventProducer;
import com.tiktok.mediaworker.service.MediaQuarantineService;
import com.tiktok.mediaworker.service.ModerationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Which decisions take a video's media off the public path, and which put it back. */
@ExtendWith(MockitoExtension.class)
class QuarantineRoutingTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Mock
    private MediaQuarantineService quarantine;

    @Mock
    private ModerationService moderationService;

    @Mock
    private VideoModerationEventProducer moderationProducer;

    @Test
    void takedown_quarantinesTheMedia() throws Exception {
        admin().onMessage(json(VideoTakenDownEvent.of("v1", 9L, "nsfw")), header("VideoTakenDownEvent"));

        verify(quarantine).quarantine("v1");
    }

    @Test
    void restore_releasesTheMedia() throws Exception {
        admin().onMessage(json(VideoRestoredEvent.of("v1", 9L, "appeal")), header("VideoRestoredEvent"));

        verify(quarantine).release("v1");
    }

    /** Same topic, and a ban has no videoId: read as a takedown it would quarantine "null". */
    @Test
    void otherModerationEvents_areIgnored() throws Exception {
        admin().onMessage(json(UserBannedEvent.of(5L, 9L, "spam")), header("UserBannedEvent"));
        admin().onMessage(json(UserBannedEvent.of(5L, 9L, "spam")), null);

        verifyNoInteractions(quarantine);
    }

    /**
     * Quarantined before the verdict goes out: if the move fails the event is redelivered and the
     * whole thing runs again, whereas a verdict already published would leave the media public.
     */
    @Test
    void rejectedVerdict_quarantinesBeforeAnnouncingIt() throws Exception {
        VideoModerationCompletedEvent rejected = VideoModerationCompletedEvent.of(
                "v2", ModerationVerdict.REJECTED, "nsfw", 0.99, 3, 10, 5L, "m", "1", null);
        when(moderationService.moderate("v2", 4)).thenReturn(rejected);

        moderation().onMessage(json(VideoTranscodedEvent.success("v2", "t", null, "h", 4)));

        InOrder order = inOrder(quarantine, moderationProducer);
        order.verify(quarantine).quarantine("v2");
        order.verify(moderationProducer).publish(rejected);
    }

    /** REVIEW waits for a human, who has to be able to watch it; APPROVED is live. */
    @Test
    void approvedAndReviewVerdicts_leaveTheMediaAlone() throws Exception {
        when(moderationService.moderate(anyString(), any())).thenReturn(
                VideoModerationCompletedEvent.of("v3", ModerationVerdict.REVIEW, "nsfw", 0.7, 1, 10, 5L, "m", "1", null),
                VideoModerationCompletedEvent.of("v3", ModerationVerdict.APPROVED, "nsfw", 0.1, 0, 10, 5L, "m", "1", null));

        moderation().onMessage(json(VideoTranscodedEvent.success("v3", "t", null, "h", 4)));
        moderation().onMessage(json(VideoTranscodedEvent.success("v3", "t", null, "h", 4)));

        verify(quarantine, never()).quarantine(anyString());
    }

    private AdminModerationEventConsumer admin() {
        return new AdminModerationEventConsumer(quarantine, objectMapper);
    }

    private VideoModerationConsumer moderation() {
        return new VideoModerationConsumer(moderationService, moderationProducer, quarantine, objectMapper);
    }

    private String json(Object event) throws Exception {
        return objectMapper.writeValueAsString(event);
    }

    private static byte[] header(String eventType) {
        return eventType.getBytes(StandardCharsets.UTF_8);
    }
}
