package com.tiktok.storyservice.event.producer;

import com.tiktok.event.story.StoryActivatedEvent;
import com.tiktok.event.story.StoryCreatedEvent;
import com.tiktok.kafka.outbox.OutboxDispatcher;
import com.tiktok.storyservice.entity.Story;
import com.tiktok.storyservice.repository.StoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

/**
 * Polls stories whose created event has not gone out yet and forwards them to media-worker for
 * screening. Per-document flags rather than an outbox collection, for the reason given on
 * {@link Story}; marking is the {@link OutboxDispatcher}'s, so a story is only marked once the
 * broker has acknowledged it.
 *
 * <p>A story whose event never leaves stays PENDING_MODERATION and invisible to everyone but its
 * owner, until the 48h ceiling removes it — never shown unscreened.
 *
 * <p>The second poll announces stories that are live to the people who may watch them
 * ({@code StoryActivatedEvent}, for notification-service), flagged by {@code announcedAt}. Never on
 * {@code story.story-events}: media-worker reads that topic without routing on a header, so it
 * would parse this event as a StoryCreatedEvent full of nulls and carry on without an error.
 *
 * <p>No parking flag for a record that cannot be built: every field of these events is an id, a
 * key or an enum this service generated, so Jackson has nothing user-supplied to refuse.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoryEventPublisher {

    static final String TOPIC = "story.story-events";
    static final String ACTIVATION_TOPIC = "story.activation-events";
    private static final int ACTIVATION_BATCH = 100;

    private final StoryRepository storyRepository;
    private final OutboxDispatcher outboxDispatcher;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 5000)
    public void publishPending() {
        List<Story> pending = storyRepository.findTop100ByEventPublishedAtIsNullAndDeletedAtIsNullOrderByCreatedAtAsc();
        if (pending.isEmpty()) {
            return;
        }

        int published = outboxDispatcher.dispatch(pending, this::toRecord,
                story -> storyRepository.markEventPublished(story.getId(), Instant.now()));

        if (published < pending.size()) {
            log.warn("Published {}/{} story events, the rest stay pending for the next poll",
                    published, pending.size());
        }
    }

    @Scheduled(fixedDelay = 5000)
    public void publishActivated() {
        List<Story> due = storyRepository.findAnnounceable(Instant.now(), ACTIVATION_BATCH);
        if (due.isEmpty()) {
            return;
        }

        int published = outboxDispatcher.dispatch(due, this::toActivationRecord,
                story -> storyRepository.markAnnounced(story.getId(), Instant.now()));

        if (published < due.size()) {
            log.warn("Published {}/{} story activations, the rest stay pending for the next poll",
                    published, due.size());
        }
    }

    private ProducerRecord<String, String> toRecord(Story story) {
        StoryCreatedEvent event = StoryCreatedEvent.of(story.getId(), story.getUserId(), story.getMediaKey(),
                story.getMediaType().name(), story.getTextVerdict());
        return new ProducerRecord<>(TOPIC, story.getId(), objectMapper.writeValueAsString(event));
    }

    /**
     * Keyed by the owner, so all of one account's announcements land on one partition, which is all
     * the 24h collapse needs. It does not keep them in the order they were made: an announcement
     * whose ack failed is re-sent by a later poll, behind ones made after it.
     */
    private ProducerRecord<String, String> toActivationRecord(Story story) {
        StoryActivatedEvent event = StoryActivatedEvent.of(story.getId(), story.getUserId());
        return new ProducerRecord<>(ACTIVATION_TOPIC, String.valueOf(story.getUserId()),
                objectMapper.writeValueAsString(event));
    }
}
