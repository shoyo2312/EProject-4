package com.tiktok.analyticsservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.analyticsservice.repository.UserSignupEventRepository;
import com.tiktok.event.user.UserRegisteredEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserRegisteredEventConsumer {

    private final UserSignupEventRepository userSignupEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "auth.user-events", groupId = "analytics-service")
    @SneakyThrows
    public void onMessage(String payload) {
        UserRegisteredEvent event = objectMapper.readValue(payload, UserRegisteredEvent.class);
        userSignupEventRepository.insert(event.eventId(), event.userId(), event.username(), event.occurredAt());
    }
}
