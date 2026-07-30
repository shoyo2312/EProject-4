package com.tiktok.notificationservice.entity;

import com.tiktok.common.id.SnowflakeIdGenerator;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.CreatedDate;
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
        @CompoundIndex(name = "recipient_feed_idx", def = "{'recipientId': 1, 'createdAt': -1}")
})
public class Notification {

    @Id
    private String id;

    private Long recipientId;

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

    @CreatedDate
    private Instant createdAt;

    public static String newId() {
        return String.valueOf(SnowflakeIdGenerator.nextId());
    }

    public void markRead() {
        this.read = true;
    }
}
