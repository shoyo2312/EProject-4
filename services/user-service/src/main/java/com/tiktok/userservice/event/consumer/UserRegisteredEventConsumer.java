package com.tiktok.userservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.user.UserRegisteredEvent;
import com.tiktok.userservice.entity.InboxEvent;
import com.tiktok.userservice.repository.InboxEventRepository;
import com.tiktok.userservice.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

        if (inboxEventRepository.existsByEventId(event.eventId())) {
            return;
        }

        userProfileService.createFromRegisteredEvent(event.userId(), event.username(), event.email());

        inboxEventRepository.save(InboxEvent.builder()
                .eventId(event.eventId())
                .eventType(event.getClass().getSimpleName())
                .build());
    }
}
