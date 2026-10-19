package com.tiktok.interactionservice.event.consumer;

import tools.jackson.databind.ObjectMapper;
import com.tiktok.event.admin.CommentRemovedEvent;
import com.tiktok.event.admin.UserBannedEvent;
import com.tiktok.event.admin.UserUnbannedEvent;
import com.tiktok.interactionservice.entity.UserBan;
import com.tiktok.interactionservice.service.CommentService;
import com.tiktok.interactionservice.service.UserBanRegistry;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * admin.moderation-events carries several unrelated event types (UserBanned, VideoTakenDown, ...)
 * whose JSON shapes overlap, so routing relies on the eventType Kafka header set by admin-service's
 * OutboxPublisher rather than on the payload.
 *
 * <p>No inbox table. A removal is an LWT conditioned on the comment still being live, so a
 * redelivery finds nothing to do and returns without touching the counter or striking the author
 * again — the property a claim table would otherwise have to provide. A ban or an unban is one
 * assignment guarded by the time it was decided ({@link UserBanRegistry#record}), so a redelivery
 * repeats it and a late one changes nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminModerationEventConsumer {

    private static final String COMMENT_REMOVED = "CommentRemovedEvent";
    private static final String USER_BANNED = "UserBannedEvent";
    private static final String USER_UNBANNED = "UserUnbannedEvent";

    private final CommentService commentService;
    private final UserBanRegistry userBanRegistry;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "admin.moderation-events", groupId = "interaction-service")
    @SneakyThrows
    public void onMessage(String payload,
                          @Header(name = "eventType", required = false) byte[] eventTypeHeader) {
        String eventType = eventTypeHeader == null ? null : new String(eventTypeHeader);

        if (COMMENT_REMOVED.equals(eventType)) {
            CommentRemovedEvent event = objectMapper.readValue(payload, CommentRemovedEvent.class);
            commentService.removeByAdmin(event.videoId(), event.commentId());
        } else if (USER_BANNED.equals(eventType)) {
            // A banned account's comments leave every listing — see UserBan.
            UserBannedEvent event = objectMapper.readValue(payload, UserBannedEvent.class);
            userBanRegistry.record(event.userId(),
                    event.bannedUntil() == null ? UserBan.PERMANENT : event.bannedUntil(), event.occurredAt());
        } else if (USER_UNBANNED.equals(eventType)) {
            UserUnbannedEvent event = objectMapper.readValue(payload, UserUnbannedEvent.class);
            userBanRegistry.record(event.userId(), null, event.occurredAt());
        } else if (eventType == null) {
            // A moderation event with no eventType header is a producer bug. Warned rather than
            // thrown: retrying cannot add a header, and this service would only DLQ every other
            // service's headerless events too.
            log.warn("Moderation event without an eventType header, dropped: {}", payload);
        } else {
            // VideoTakenDown, UserWarned, ... — other services' events on a shared topic.
            log.debug("Ignoring moderation eventType={}", eventType);
        }
    }
}
