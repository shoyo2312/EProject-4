package com.tiktok.mediaworker.event.consumer;

import tools.jackson.databind.ObjectMapper;
import com.tiktok.event.admin.UserBannedEvent;
import com.tiktok.event.admin.VideoRestoredEvent;
import com.tiktok.event.admin.VideoTakenDownEvent;
import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.event.video.VideoModerationCompletedEvent;
import com.tiktok.event.video.VideoTranscodedEvent;
import com.tiktok.mediaworker.event.producer.VideoModerationEventProducer;
import com.tiktok.mediaworker.service.MediaQuarantineService;
import com.tiktok.mediaworker.service.MediaQuarantineService.Hold;
import com.tiktok.mediaworker.service.ModerationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Which decisions take a video's media off the public path, and which put it back. */
@ExtendWith(MockitoExtension.class)
class QuarantineRoutingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private MediaQuarantineService quarantine;

    @Mock
    private ModerationService moderationService;

    @Mock
    private VideoModerationEventProducer moderationProducer;

    @Test
    void takedown_quarantinesTheMedia() throws Exception {
        admin().onMessage(json(VideoTakenDownEvent.of("v1", 9L, "nsfw")), header("VideoTakenDownEvent"));

        verify(quarantine).hold("v1", Hold.TAKEN_DOWN);
    }

    @Test
    void restore_liftsWhatTheRestoreLifts() throws Exception {
        admin().onMessage(json(VideoRestoredEvent.of("v1", 9L, "appeal")), header("VideoRestoredEvent"));

        verify(quarantine).restore("v1");
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
        order.verify(quarantine).hold("v2", Hold.MODERATION);
        order.verify(moderationProducer).publish(rejected);
    }

    /**
     * REVIEW is a video nobody has cleared, and it used to stay public by URL while it waited.
     * The admin deciding it watches it through the console's signed URLs.
     */
    @Test
    void reviewVerdict_isHeldToo() throws Exception {
        when(moderationService.moderate("v4", 4)).thenReturn(
                VideoModerationCompletedEvent.of("v4", ModerationVerdict.REVIEW, "nsfw", 0.7, 1, 10, 5L, "m", "1", null));

        moderation().onMessage(json(VideoTranscodedEvent.success("v4", "t", null, "h", 4)));

        verify(quarantine).hold("v4", Hold.MODERATION);
    }

    @Test
    void approvedVerdict_leavesTheMediaAlone() throws Exception {
        when(moderationService.moderate("v3", 4)).thenReturn(
                VideoModerationCompletedEvent.of("v3", ModerationVerdict.APPROVED, "nsfw", 0.1, 0, 10, 5L, "m", "1", null));

        moderation().onMessage(json(VideoTranscodedEvent.success("v3", "t", null, "h", 4)));

        verifyNoInteractions(quarantine);
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
