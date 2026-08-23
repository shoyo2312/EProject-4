package com.tiktok.authservice.event.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.user.SocialAvatarDiscoveredEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Announces the provider picture a social sign-in arrived with.
 *
 * <p>On a topic of its own rather than {@code auth.user-events}: that one carries
 * {@code UserRegisteredEvent} and nothing else, and both of its consumers parse the payload
 * straight into that class without consulting any type header — a second shape posted there would
 * deserialise into a registration with every field null and quietly create garbage profiles.
 *
 * <p>Sent straight to the broker rather than through the outbox, the one place in this service that
 * does so. The outbox exists to keep an event atomic with a database write, and a sign-in that
 * finds an existing account writes nothing to be atomic with. What matters more here is that Kafka
 * being down must not fail a login over a cosmetic picture, so the send is fire-and-forget and a
 * failure is only logged: the next sign-in publishes the same thing again, which leaves the avatar
 * one login behind instead of lost.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SocialAvatarEventProducer {

    private static final String TOPIC = "auth.social-avatar-events";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @SneakyThrows
    public void publish(Long userId, String avatarUrl) {
        SocialAvatarDiscoveredEvent event = SocialAvatarDiscoveredEvent.of(userId, avatarUrl);
        kafkaTemplate.send(TOPIC, String.valueOf(userId), objectMapper.writeValueAsString(event))
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        log.warn("Could not announce the provider avatar of user {}: {}",
                                userId, failure.getMessage());
                    }
                });
    }
}
