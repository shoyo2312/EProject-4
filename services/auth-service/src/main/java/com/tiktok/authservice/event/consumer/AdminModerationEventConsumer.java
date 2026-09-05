package com.tiktok.authservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.authservice.service.UserModerationService;
import com.tiktok.event.admin.UserBannedEvent;
import com.tiktok.event.admin.UserUnbannedEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * admin.moderation-events carries several unrelated event types whose JSON shapes are
 * indistinguishable (UserBanned and UserUnbanned have identical fields), so the eventType header
 * set by admin-service's OutboxPublisher is the only thing routing can use.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminModerationEventConsumer {

    private static final String USER_BANNED = "UserBannedEvent";
    private static final String USER_UNBANNED = "UserUnbannedEvent";

    private final UserModerationService userModerationService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "admin.moderation-events", groupId = "auth-service")
    @SneakyThrows
    public void onMessage(String payload,
                          @Header(name = "eventType", required = false) byte[] eventTypeHeader) {
        String eventType = eventTypeHeader == null ? null : new String(eventTypeHeader);

        if (USER_BANNED.equals(eventType)) {
            UserBannedEvent event = objectMapper.readValue(payload, UserBannedEvent.class);
            userModerationService.ban(event.userId(), event.adminId());
        } else if (USER_UNBANNED.equals(eventType)) {
            UserUnbannedEvent event = objectMapper.readValue(payload, UserUnbannedEvent.class);
            userModerationService.unban(event.userId(), event.adminId());
        } else if (eventType == null) {
            // Routing is header-only, so a ban that arrives without one would be dropped in
            // silence and the account would stay usable with nothing to say why. Logged rather
            // than thrown: a retry cannot add a header the producer never set.
            log.warn("Moderation event without an eventType header, dropped: {}", payload);
        } else {
            // VideoTakenDown, ProductSuspended, ... — other services' events on a shared topic.
            log.debug("Ignoring moderation eventType={}", eventType);
        }
    }
}
