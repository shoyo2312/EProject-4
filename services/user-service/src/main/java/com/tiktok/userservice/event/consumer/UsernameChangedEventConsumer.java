package com.tiktok.userservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.common.id.SnowflakeIdGenerator;
import com.tiktok.event.user.UsernameChangedEvent;
import com.tiktok.userservice.repository.InboxEventRepository;
import com.tiktok.userservice.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Keeps the profile's copy of the handle in step with auth-service, so {@code /@newname} resolves
 * and lists show the new one.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UsernameChangedEventConsumer {

    private final InboxEventRepository inboxEventRepository;
    private final UserProfileService userProfileService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "auth.username-events", groupId = "user-service")
    @Transactional
    @SneakyThrows
    public void onMessage(String payload) {
        UsernameChangedEvent event = objectMapper.readValue(payload, UsernameChangedEvent.class);

        // Claimed in the same transaction as the update, so a delivery that throws because the
        // profile does not exist yet gives its claim back and the retry can take it.
        int claimed = inboxEventRepository.tryClaim(
                SnowflakeIdGenerator.nextId(),
                event.eventId(),
                event.getClass().getSimpleName(),
                Instant.now());

        if (claimed == 0) {
            log.info("Skipping already-processed UsernameChangedEvent eventId={}", event.eventId());
            return;
        }

        userProfileService.applyUsernameChange(event.userId(), event.username());
    }
}
