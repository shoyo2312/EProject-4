package com.tiktok.notificationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.user.UserRegisteredEvent;
import com.tiktok.notificationservice.entity.NotificationType;
import com.tiktok.notificationservice.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserRegisteredEventConsumer {

    private final IdempotentEventProcessor idempotentEventProcessor;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "auth.user-events", groupId = "notification-service")
    @SneakyThrows
    public void onMessage(String payload) {
        UserRegisteredEvent event = objectMapper.readValue(payload, UserRegisteredEvent.class);

        // Was a check on existsByEventId followed by a save afterwards, which let two concurrent
        // deliveries both pass the check and send two welcome notes. See IdempotentEventProcessor.
        idempotentEventProcessor.runOnce(event.eventId(), event.getClass().getSimpleName(), () ->
                notificationService.create(
                        event.userId(),
                        null,
                        NotificationType.SYSTEM,
                        "Welcome to TikTok!",
                        "Hi " + event.username() + ", thanks for joining us.",
                        null));
    }
}
