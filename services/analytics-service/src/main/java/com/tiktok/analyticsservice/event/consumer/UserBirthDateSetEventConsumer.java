package com.tiktok.analyticsservice.event.consumer;

import com.tiktok.analyticsservice.repository.ViewerProfileRepository;
import com.tiktok.event.user.UserBirthDateSetEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** A birth date set after sign-up (social sign-ins, older accounts). One per account, ever. */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserBirthDateSetEventConsumer {

    private final ViewerProfileRepository viewerProfileRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "user.profile-events", groupId = "analytics-service")
    @SneakyThrows
    public void onMessage(String payload) {
        UserBirthDateSetEvent event = objectMapper.readValue(payload, UserBirthDateSetEvent.class);
        if (event.userId() == null || event.birthDate() == null || event.occurredAt() == null) {
            log.warn("Skipping birth date event {} with a missing field", event.eventId());
            return;
        }
        viewerProfileRepository.upsert(event.userId(), event.birthDate(), event.occurredAt());
    }
}
