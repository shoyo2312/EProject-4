package com.tiktok.notificationservice.event.consumer;

import com.tiktok.event.story.StoryActivatedEvent;
import com.tiktok.notificationservice.client.StoryAnnouncementClient;
import com.tiktok.notificationservice.client.StoryAnnouncementClient.Announcement;
import com.tiktok.notificationservice.client.StoryAudienceClient;
import com.tiktok.notificationservice.client.StoryAudienceClient.Page;
import com.tiktok.notificationservice.entity.NotificationType;
import com.tiktok.notificationservice.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Tells the followers of an account that it posted a story: one inbox entry each, for the people
 * allowed to watch it.
 *
 * <p>The event carries the story and its owner but neither the story's state nor its visibility,
 * so both are read when the fan-out runs, not when the event was published: a late first read of
 * the topic, a DLT replay or a user-service outage must not announce a story that was deleted,
 * expired, narrowed to PRIVATE or whose owner has since been banned.
 *
 * <p>A failure anywhere in the fan-out releases the claim and rethrows, and kafka-lib redelivers
 * the event from its first page. The followers already told are told a second time, which
 * {@link NotificationService#create} collapses — one entry per follower per owner per day — so a
 * fan-out that failed halfway never doubles.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoryActivationEventConsumer {

    private final IdempotentEventProcessor idempotentEventProcessor;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final StoryAnnouncementClient storyAnnouncementClient;
    private final StoryAudienceClient storyAudienceClient;

    @KafkaListener(topics = "story.activation-events", groupId = "notification-service")
    public void onMessage(String payload) {
        StoryActivatedEvent event = objectMapper.readValue(payload, StoryActivatedEvent.class);

        idempotentEventProcessor.runOnce(event.eventId(), event.getClass().getSimpleName(), () ->
                storyAnnouncementClient.announcement(event.storyId()).ifPresentOrElse(
                        story -> announce(event.storyId(), story),
                        () -> log.info("Story {} is gone (deleted, expired, not live or its owner hidden): nobody is told",
                                event.storyId())));
    }

    /**
     * No block check per recipient: the audience endpoint already leaves out blocks in either
     * direction, closed accounts and followers who muted the owner.
     *
     * <p>ponytail: recipients go one by one and FCM sends synchronously per device, so a poll's fan-outs must fit in max.poll.interval.ms
     * (5 min); a page slower than downstream.timeout-millis reads as an outage and stalls the partition until DOWNSTREAM_TIMEOUT_MILLIS is
     * raised or UserFollowRepository's (following_id, follower_id) index exists; a bulk insert is the upgrade for the fan-out itself.
     */
    private void announce(String storyId, Announcement story) {
        String scope = switch (story.visibility()) {
            case "PUBLIC" -> "FOLLOWERS";
            case "FRIENDS" -> "FRIENDS";
            default -> null; // PRIVATE: nobody but the owner may watch it
        };
        if (scope == null) {
            log.info("Story {} is {} now: nobody is told", storyId, story.visibility());
            return;
        }

        String ownerReference = String.valueOf(story.userId());
        Long after = null;
        do {
            Page page = storyAudienceClient.page(story.userId(), scope, after);
            for (Long recipientId : page.userIds()) {
                notificationService.create(recipientId, story.userId(), NotificationType.STORY,
                        "New story", "Someone posted a new story.", ownerReference);
            }
            after = page.next();
        } while (after != null);
    }
}
