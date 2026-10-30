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
import java.util.List;
import java.util.Map;

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
        @CompoundIndex(name = "conversation_sent_id_idx", def = "{'conversationId': 1, 'sentAt': -1, '_id': -1}"),
        // The whole "one message before the request is accepted" rule: at most one message per
        // conversation may carry requestMessage = true, and the database refuses a second one
        // atomically. A count-then-save would let two concurrent sends both see zero; this cannot.
        // It needs no transaction (this Mongo is a standalone, which has none) and leaks no
        // allowance when a save fails, because the allowance is the row itself.
        @CompoundIndex(name = "one_request_message_idx", def = "{'conversationId': 1}", unique = true,
                partialFilter = "{'requestMessage': true}"),
        // Partial rather than sparse=true: Mongo forbids combining both options. Missing keys
        // do not participate; null fields are omitted by Spring Data's default field mapping.
        @CompoundIndex(name = "message_client_request_idx",
                def = "{'conversationId': 1, 'senderId': 1, 'clientRequestId': 1}", unique = true,
                partialFilter = "{'clientRequestId': {'$exists': true}}")
})
public class Message {

    @Id
    private String id;

    private String conversationId;

    private Long senderId;

    /** Null on messages stored before types existed; read through {@link #getType()}, which says TEXT. */
    private MessageType type;

    /** TEXT content or an optional VIDEO caption; absent on STICKER messages. */
    private String content;

    /** Present only on STICKER messages: an id in the sticker catalog, never a URL or a storage key. */
    private String stickerId;

    private String videoId;

    /** Present only on STORY_REPLY: the id only. The story is resolved for each viewer on every render, never copied. */
    private String storyId;

    private String clientRequestId;

    private Instant sentAt;

    private Instant deletedAt;

    /** True only on the single message sent while the conversation was a pending request. */
    private Boolean requestMessage;

    /**
     * The message this one answers. Only the id is stored: the quote is read from the original each
     * time it is shown, so recalling or hiding the original changes every quote of it at once and
     * there is no copy of its text that could outlive a recall.
     */
    private String replyToMessageId;

    /**
     * Set when the sender takes the message back, for both sides. {@code content}, {@code stickerId},
     * {@code videoId} and {@code reactions} are removed in the same update, so nothing that reads
     * the row — history, export, a quote — can return what was recalled. The row itself stays: its
     * id, position in time, and the {@code requestMessage} slot it may hold.
     */
    private Instant recalledAt;

    /** userId to emoji: one reaction per person, set and cleared with single-field updates. */
    private Map<String, String> reactions;

    /**
     * People who deleted this message from their own side. Per viewer on purpose: the global
     * {@code deletedAt} would take it away from both.
     */
    private List<Long> hiddenFor;

    /** Bumped by every reaction change and by the recall; lets a client discard an update that arrives late. */
    private Long revision;

    public static String newId() {
        return String.valueOf(SnowflakeIdGenerator.nextId());
    }

    public MessageType getType() {
        return type == null ? MessageType.TEXT : type;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean isRecalled() {
        return recalledAt != null;
    }

    public long getRevision() {
        return revision == null ? 0L : revision;
    }

    public boolean isHiddenFor(Long userId) {
        return hiddenFor != null && hiddenFor.contains(userId);
    }
}
