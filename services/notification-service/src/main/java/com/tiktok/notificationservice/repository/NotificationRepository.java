package com.tiktok.notificationservice.repository;

import com.tiktok.notificationservice.entity.Notification;
import com.tiktok.notificationservice.entity.NotificationType;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.Optional;

public interface NotificationRepository extends MongoRepository<Notification, String>, NotificationRepositoryCustom {

    /** Backed by {@code recipient_unread_idx}, so the badge does not scan the whole inbox. */
    long countByRecipientIdAndReadFalse(Long recipientId);

    /**
     * The entry that already told this recipient about this actor doing this to this reference,
     * if it is recent enough to still count as announced. Backed by {@code collapse_idx}; see
     * {@link NotificationType#collapsesRepeats()} for which types ask.
     */
    Optional<Notification> findFirstByRecipientIdAndActorIdAndTypeAndReferenceIdAndCreatedAtAfter(
            Long recipientId, Long actorId, NotificationType type, String referenceId, Instant after);
}
