package com.tiktok.adminservice.event.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.adminservice.entity.ModerationAction;
import com.tiktok.adminservice.entity.ModerationActionType;
import com.tiktok.adminservice.entity.OutboxEvent;
import com.tiktok.adminservice.repository.OutboxEventRepository;
import com.tiktok.event.DomainEvent;
import com.tiktok.event.admin.ProductReactivatedEvent;
import com.tiktok.event.admin.ProductSuspendedEvent;
import com.tiktok.event.admin.UserBannedEvent;
import com.tiktok.event.admin.UserUnbannedEvent;
import com.tiktok.event.admin.VideoRestoredEvent;
import com.tiktok.event.admin.VideoTakenDownEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;

/**
 * Translates a moderation decision into the matching admin.* domain event and writes it to
 * the outbox. DISMISS_REPORT and WARN_USER have no downstream event — they're recorded in
 * the audit log only, since no other service needs to react to them.
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
            case BAN_USER -> UserBannedEvent.of(Long.valueOf(targetId), adminId, reason);
            case UNBAN_USER -> UserUnbannedEvent.of(Long.valueOf(targetId), adminId, reason);
            case TAKEDOWN_VIDEO -> VideoTakenDownEvent.of(targetId, adminId, reason);
            case RESTORE_VIDEO -> VideoRestoredEvent.of(targetId, adminId, reason);
            case SUSPEND_PRODUCT -> ProductSuspendedEvent.of(Long.valueOf(targetId), adminId, reason);
            case REACTIVATE_PRODUCT -> ProductReactivatedEvent.of(Long.valueOf(targetId), adminId, reason);
            case WARN_USER, DISMISS_REPORT -> null;
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
