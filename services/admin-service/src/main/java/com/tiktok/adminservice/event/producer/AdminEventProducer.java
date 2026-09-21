package com.tiktok.adminservice.event.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.adminservice.entity.CommentTarget;
import com.tiktok.adminservice.entity.ModerationAction;
import com.tiktok.adminservice.entity.ModerationActionType;
import com.tiktok.adminservice.entity.OutboxEvent;
import com.tiktok.adminservice.repository.OutboxEventRepository;
import com.tiktok.event.DomainEvent;
import com.tiktok.event.admin.CommentRemovedEvent;
import com.tiktok.event.admin.UserBannedEvent;
import com.tiktok.event.admin.UserUnbannedEvent;
import com.tiktok.event.admin.UserWarnedEvent;
import com.tiktok.event.admin.VideoRestoredEvent;
import com.tiktok.event.admin.VideoTakenDownEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;

/**
 * Translates a moderation decision into the matching admin.* domain event and writes it to
 * the outbox. DISMISS_REPORT has no downstream event — nothing happened, so there is nothing to
 * tell anyone. A warning does: it changes no state anywhere, and telling the person is the only
 * effect it can have.
 */
@Component
@RequiredArgsConstructor
public class AdminEventProducer {

    private static final String AGGREGATE_TYPE = "MODERATION_ACTION";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public void publishFor(ModerationAction action) {
        DomainEvent event = toEvent(action);
        if (event == null) {
            return;
        }
        writeToOutbox(action.getTargetId(), event);
    }

    private DomainEvent toEvent(ModerationAction action) {
        Long adminId = action.getAdminId();
        String reason = action.getReason();
        String targetId = action.getTargetId();

        return switch (action.getActionType()) {
            case BAN_USER -> UserBannedEvent.of(Long.valueOf(targetId), adminId, reason, action.getBannedUntil());
            case UNBAN_USER -> UserUnbannedEvent.of(Long.valueOf(targetId), adminId, reason);
            case TAKEDOWN_VIDEO -> VideoTakenDownEvent.of(targetId, adminId, reason);
            case RESTORE_VIDEO -> VideoRestoredEvent.of(targetId, adminId, reason);
            case REMOVE_COMMENT -> {
                CommentTarget target = CommentTarget.parse(targetId);
                yield CommentRemovedEvent.of(target.videoId(), target.commentId(), adminId, reason);
            }
            // A warning changes nothing about the platform, so it carries the report's target
            // rather than a user id: notification-service resolves a video to its owner.
            case WARN_USER -> UserWarnedEvent.of(action.getTargetType().name(), targetId, adminId, reason);
            case DISMISS_REPORT -> null;
        };
    }

    @SneakyThrows
    private void writeToOutbox(String aggregateId, DomainEvent event) {
        OutboxEvent outboxEvent = OutboxEvent.builder()
                .aggregateType(AGGREGATE_TYPE)
                .aggregateId(aggregateId)
                .eventType(event.getClass().getSimpleName())
                .payload(objectMapper.writeValueAsString(event))
                .build();

        outboxEventRepository.save(outboxEvent);
    }
}
