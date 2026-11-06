package com.tiktok.analyticsservice.event.consumer;

import tools.jackson.databind.ObjectMapper;
import com.tiktok.analyticsservice.repository.UserSignupEventRepository;
import com.tiktok.analyticsservice.repository.ViewerProfileRepository;
import com.tiktok.event.user.UserRegisteredEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserRegisteredEventConsumer {

    private final UserSignupEventRepository userSignupEventRepository;
    private final ViewerProfileRepository viewerProfileRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "auth.user-events", groupId = "analytics-service")
    @SneakyThrows
    public void onMessage(String payload) {
        UserRegisteredEvent event = objectMapper.readValue(payload, UserRegisteredEvent.class);
        userSignupEventRepository.insert(event.eventId(), event.userId(), event.username(), event.occurredAt());
        // A password sign-up carries the birth date; social sign-ups and older events do not.
        if (event.birthDate() != null && event.userId() != null) {
            viewerProfileRepository.upsert(event.userId(), event.birthDate(), event.occurredAt());
        }
    }
}
