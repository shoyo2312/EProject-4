package com.tiktok.notificationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.user.UserRegisteredEvent;
import com.tiktok.notificationservice.entity.NotificationType;
import com.tiktok.notificationservice.entity.ProcessedEvent;
import com.tiktok.notificationservice.repository.ProcessedEventRepository;
import com.tiktok.notificationservice.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserRegisteredEventConsumer {

    private final NotificationService notificationService;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "auth.user-events", groupId = "notification-service")
    @SneakyThrows
    public void onMessage(String payload) {
        UserRegisteredEvent event = objectMapper.readValue(payload, UserRegisteredEvent.class);

        if (processedEventRepository.existsByEventId(event.eventId())) {
            return;
        }

        notificationService.create(
                event.userId(),
                NotificationType.SYSTEM,
                "Welcome to TikTok!",
                "Hi " + event.username() + ", thanks for joining us.",
                null);

        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(event.eventId())
                .eventType(event.getClass().getSimpleName())
                .build());
    }
}
