package com.tiktok.chatservice.entity;

import com.tiktok.common.id.SnowflakeIdGenerator;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 1:1 direct-message thread. {@code participantKey} is a canonical "smallerId_largerId"
 * string with a unique index so concurrent "start conversation" calls between the same two
 * users converge on one document instead of racing to create duplicates.
 */
@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "conversations")
@CompoundIndexes({
        @CompoundIndex(name = "participant_key_idx", def = "{'participantKey': 1}", unique = true)
})
public class Conversation {

    @Id
    private String id;

    @Indexed
    private List<Long> participantIds;

    private String participantKey;

    private String lastMessageContent;

    private Long lastMessageSenderId;

    private Instant lastMessageAt;

    @CreatedDate
    private Instant createdAt;

    @Builder.Default
    private Map<String, Instant> lastReadAt = new HashMap<>();

    private Instant deletedAt;

    @Version
    private Long version;

    public static String newId() {
        return String.valueOf(SnowflakeIdGenerator.nextId());
    }

    public static String buildParticipantKey(Long userIdA, Long userIdB) {
        long lo = Math.min(userIdA, userIdB);
        long hi = Math.max(userIdA, userIdB);
        return lo + "_" + hi;
    }

    public boolean hasParticipant(Long userId) {
        return participantIds != null && participantIds.contains(userId);
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void recordMessage(Long senderId, String content, Instant sentAt) {
        this.lastMessageContent = content;
        this.lastMessageSenderId = senderId;
        this.lastMessageAt = sentAt;
        markRead(senderId, sentAt);
    }

    public void markRead(Long userId, Instant readAt) {
        if (this.lastReadAt == null) {
            this.lastReadAt = new HashMap<>();
        }
        this.lastReadAt.put(String.valueOf(userId), readAt);
    }
}
