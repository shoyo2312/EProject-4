package com.tiktok.notificationservice.repository;

import com.tiktok.notificationservice.entity.Notification;
import com.tiktok.notificationservice.entity.NotificationType;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends MongoRepository<Notification, String> {

    List<Notification> findByRecipientIdOrderByCreatedAtDesc(Long recipientId);

    List<Notification> findByRecipientIdAndReadFalse(Long recipientId);

    long countByRecipientIdAndReadFalse(Long recipientId);

    /**
     * The entry that already told this recipient about this actor doing this to this reference,
     * if it is recent enough to still count as announced. Backed by {@code collapse_idx}; see
     * {@link NotificationType#collapsesRepeats()} for which types ask.
     */
    Optional<Notification> findFirstByRecipientIdAndActorIdAndTypeAndReferenceIdAndCreatedAtAfter(
            Long recipientId, Long actorId, NotificationType type, String referenceId, Instant after);
}
