package com.tiktok.userservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.common.id.SnowflakeIdGenerator;
import com.tiktok.event.user.UserRegisteredEvent;
import com.tiktok.userservice.repository.InboxEventRepository;
import com.tiktok.userservice.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserRegisteredEventConsumer {

    private final InboxEventRepository inboxEventRepository;
    private final UserProfileService userProfileService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "auth.user-events", groupId = "user-service")
    @Transactional
    @SneakyThrows
    public void onMessage(String payload) {
        UserRegisteredEvent event = objectMapper.readValue(payload, UserRegisteredEvent.class);

        // Claim the eventId atomically before doing any work: if two redeliveries of the
        // same event race, only one insert wins and the other gets 0 rows, avoiding a unique
        // constraint violation escaping the transaction and duplicate profile creation.
        int claimed = inboxEventRepository.tryClaim(
                SnowflakeIdGenerator.nextId(),
                event.eventId(),
                event.getClass().getSimpleName(),
                Instant.now());

        if (claimed == 0) {
            log.info("Skipping already-processed UserRegisteredEvent eventId={}", event.eventId());
            return;
        }

        userProfileService.createFromRegisteredEvent(event.userId(), event.username());
    }
}
