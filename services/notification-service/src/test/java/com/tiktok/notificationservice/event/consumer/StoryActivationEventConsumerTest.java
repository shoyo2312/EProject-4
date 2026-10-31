package com.tiktok.notificationservice.event.consumer;

import com.tiktok.event.story.StoryActivatedEvent;
import com.tiktok.notificationservice.client.StoryAnnouncementClient;
import com.tiktok.notificationservice.client.StoryAnnouncementClient.Announcement;
import com.tiktok.notificationservice.client.StoryAudienceClient;
import com.tiktok.notificationservice.client.StoryAudienceClient.Page;
import com.tiktok.notificationservice.entity.NotificationType;
import com.tiktok.notificationservice.repository.ProcessedEventRepository;
import com.tiktok.notificationservice.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.ConnectException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class StoryActivationEventConsumerTest {

    private static final StoryActivatedEvent EVENT = StoryActivatedEvent.of("s-1", 7L);

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private StoryAnnouncementClient storyAnnouncementClient;

    @Mock
    private StoryAudienceClient storyAudienceClient;

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    private void deliver() {
        new StoryActivationEventConsumer(
                new IdempotentEventProcessor(processedEventRepository), notificationService, objectMapper,
                storyAnnouncementClient, storyAudienceClient)
                .onMessage(objectMapper.writeValueAsString(EVENT));
    }

    private void claimed() {
        when(processedEventRepository.tryClaim(eq(EVENT.eventId()), anyString())).thenReturn(true);
    }

    private void announcedAs(String visibility) {
        when(storyAnnouncementClient.announcement("s-1")).thenReturn(Optional.of(new Announcement(7L, visibility)));
    }

    private void verifyTold(long recipient) {
        verify(notificationService).create(
                recipient, 7L, NotificationType.STORY, "New story", "Someone posted a new story.", "7");
    }

    @Test
    void publicStoryNotifiesEveryFollowerAcrossPages() {
        claimed();
        announcedAs("PUBLIC");
        when(storyAudienceClient.page(7L, "FOLLOWERS", null)).thenReturn(new Page(List.of(11L, 12L), 12L));
        when(storyAudienceClient.page(7L, "FOLLOWERS", 12L)).thenReturn(new Page(List.of(13L), null));

        deliver();

        verifyTold(11);
        verifyTold(12);
        verifyTold(13);
        verifyNoMoreInteractions(notificationService);
    }

    /** The event names an owner too, but the re-check is the current word on whose story this is. */
    @Test
    void theOwnerIsTheOneTheRecheckNames() {
        claimed(); // the event is for owner 7
        when(storyAnnouncementClient.announcement("s-1")).thenReturn(Optional.of(new Announcement(8L, "PUBLIC")));
        when(storyAudienceClient.page(8L, "FOLLOWERS", null)).thenReturn(new Page(List.of(11L), null));

        deliver();

        verify(storyAudienceClient).page(8L, "FOLLOWERS", null);
        verify(notificationService).create(
                11L, 8L, NotificationType.STORY, "New story", "Someone posted a new story.", "8");
        verifyNoMoreInteractions(notificationService, storyAudienceClient);
    }

    @Test
    void friendsStoryAsksForFriends() {
        claimed();
        announcedAs("FRIENDS");
        when(storyAudienceClient.page(7L, "FRIENDS", null)).thenReturn(new Page(List.of(11L), null));

        deliver();

        verifyTold(11);
        verify(storyAudienceClient).page(7L, "FRIENDS", null);
        verifyNoMoreInteractions(notificationService, storyAudienceClient);
    }

    /** The event does not carry visibility: the owner may have narrowed the story since it was published. */
    @Test
    void privateStoryNotifiesNobody(CapturedOutput output) {
        claimed();
        announcedAs("PRIVATE");

        deliver();

        verifyNoInteractions(storyAudienceClient, notificationService);
        // What someone reads when asking why nobody heard about a story they posted.
        assertThat(output.getOut() + output.getErr()).containsOnlyOnce("s-1").contains("PRIVATE");
    }

    /** Deleted, expired, never went live, or its owner hidden since the event was published. */
    @Test
    void storyThatIsGoneNotifiesNobody(CapturedOutput output) {
        claimed();
        when(storyAnnouncementClient.announcement("s-1")).thenReturn(Optional.empty());

        deliver();

        verifyNoInteractions(storyAudienceClient, notificationService);
        assertThat(output.getOut() + output.getErr()).containsOnlyOnce("s-1");
    }

    /** story-service publishes the same story twice when an ack times out; the eventId is the same. */
    @Test
    void redeliveredEventDoesNothing() {
        when(processedEventRepository.tryClaim(eq(EVENT.eventId()), anyString())).thenReturn(false);

        deliver();

        verifyNoInteractions(storyAnnouncementClient, storyAudienceClient, notificationService);
    }

    /**
     * The claim goes back so kafka-lib's redelivery can run the fan-out again. The followers of page
     * one are told a second time on that retry, which NotificationServiceImpl collapses: that is
     * what keeps a half-finished fan-out from doubling.
     */
    @Test
    void failureOnALaterPageRethrowsAndReleasesTheClaim() {
        ResourceAccessException outage = new ResourceAccessException(
                "I/O error on GET request for \"http://user-service/api/v1/users/internal/story-audience\"",
                new ConnectException("Connection refused"));
        claimed();
        announcedAs("PUBLIC");
        when(storyAudienceClient.page(7L, "FOLLOWERS", null)).thenReturn(new Page(List.of(11L, 12L), 12L));
        when(storyAudienceClient.page(7L, "FOLLOWERS", 12L)).thenThrow(outage);

        assertThatThrownBy(this::deliver).isSameAs(outage);

        verify(processedEventRepository).releaseClaim(EVENT.eventId());
        verifyTold(11);
        verifyTold(12);
        verifyNoMoreInteractions(notificationService);
    }
}
