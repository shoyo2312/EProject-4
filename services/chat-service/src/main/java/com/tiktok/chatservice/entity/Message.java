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
        // _id is in the key because the history pages by keyset on (sentAt, _id) — see
        // MessageRepositoryCustom.findPage — and Mongo can only take a compound sort from an index
        // carrying every field of it. Without _id, every page sorts everything older than its
        // cursor in memory. A new name because auto-index-creation will not redefine an existing
        // one; the two-field conversation_sent_idx it replaces stays on databases created before
        // it until dropped: db.messages.dropIndex("conversation_sent_idx").
        @CompoundIndex(name = "conversation_sent_id_idx", def = "{'conversationId': 1, 'sentAt': -1, '_id': -1}")
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
