package com.tiktok.notificationservice.entity;

import com.tiktok.common.id.SnowflakeIdGenerator;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "notifications")
@CompoundIndexes({
        @CompoundIndex(name = "recipient_feed_idx", def = "{'recipientId': 1, 'createdAt': -1}"),
        // Serves the duplicate lookup NotificationServiceImpl runs before every collapsible
        // create — without it that check scans the recipient's whole inbox on every like.
        @CompoundIndex(name = "collapse_idx",
                def = "{'recipientId': 1, 'actorId': 1, 'type': 1, 'referenceId': 1, 'createdAt': -1}")
})
public class Notification {

    @Id
    private String id;

    private Long recipientId;

    /**
     * Who did the thing (liked/commented/shared/followed) — null for SYSTEM, where there is no
     * actor. The client resolves this to an avatar and handle itself; this service never reads
     * user-service, same reasoning as {@link #referenceId}.
     */
    private Long actorId;

    private NotificationType type;

    private String title;

    private String body;

    /**
     * Id of the entity the notification refers to (commentId, videoId, follower userId, ...) —
     * type-dependent, opaque to this service, used by the client to deep-link.
     */
    private String referenceId;

    @Builder.Default
    private boolean read = false;

    /**
     * Stamped by the builder, not by {@code @CreatedDate}: this document assigns its own
     * {@code _id}, so Spring Data's auditing sees the entity as already-persisted and only ever
     * fills a last-modified field. The annotation silently left this null, which reached the
     * client as an epoch-0 timestamp ("690mo ago") and made the newest-first sort meaningless —
     * Mongo orders a missing field below every value.
     */
    @Builder.Default
    private Instant createdAt = Instant.now();

    public static String newId() {
        return String.valueOf(SnowflakeIdGenerator.nextId());
    }

    public void markRead() {
        this.read = true;
    }
}
