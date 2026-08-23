package com.tiktok.mediaworker.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.user.AvatarMirroredEvent;
import com.tiktok.event.user.SocialAvatarDiscoveredEvent;
import com.tiktok.mediaworker.event.producer.AvatarMirroredEventProducer;
import com.tiktok.mediaworker.service.AvatarMirrorService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Turns "the provider has a picture for this user" into a copy of it in our own bucket.
 *
 * <p>No inbox table, for the reason {@link VideoEventConsumer} gives: a repeat writes the same
 * object at the same key and announces the same URL, and the durable change happens in
 * user-service, whose own consumer is where a duplicate actually has to be rejected.
 *
 * <p>A URL that cannot be fetched — expired, refused, not an image, from a host we do not trust —
 * ends up on the DLT after kafka-lib's attempts, and that is the right place for it: nothing about
 * the failure will be different in three seconds, and the next sign-in announces a fresh URL
 * regardless. The profile keeps whatever avatar it already had in the meantime.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SocialAvatarEventConsumer {

    private final AvatarMirrorService avatarMirrorService;
    private final AvatarMirroredEventProducer eventProducer;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "auth.social-avatar-events", groupId = "media-worker")
    @SneakyThrows
    public void onMessage(String payload) {
        SocialAvatarDiscoveredEvent event =
                objectMapper.readValue(payload, SocialAvatarDiscoveredEvent.class);

        String mirrored = avatarMirrorService.mirror(event.userId(), event.avatarUrl());

        eventProducer.publish(AvatarMirroredEvent.of(event.userId(), event.avatarUrl(), mirrored));
    }
}
