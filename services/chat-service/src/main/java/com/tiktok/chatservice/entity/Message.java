package com.tiktok.chatservice.entity;

import com.tiktok.common.id.SnowflakeIdGenerator;
import lombok.AllArgsConstructor;
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
@Document(collection = "messages")
@CompoundIndexes({
        @CompoundIndex(name = "conversation_sent_idx", def = "{'conversationId': 1, 'sentAt': -1}")
})
public class Message {

    @Id
    private String id;

    private String conversationId;

    private Long senderId;

    private String content;

    private Instant sentAt;

    private Instant deletedAt;

    public static String newId() {
        return String.valueOf(SnowflakeIdGenerator.nextId());
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
