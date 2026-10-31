package com.tiktok.chatservice.repository;

import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.entity.MessageRequestState;
import com.tiktok.chatservice.entity.MessageType;
import com.tiktok.chatservice.entity.ReceiptMark;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.List;

import static org.springframework.data.mongodb.core.query.Criteria.where;

@RequiredArgsConstructor
public class ConversationRepositoryImpl implements ConversationRepositoryCustom {

    private final MongoTemplate mongoTemplate;

    @Override
    public void recordMessage(String conversationId, Long senderId, MessageType type, String content, Instant sentAt) {
        // Conditional on the preview being older, so two sends landing out of order leave the
        // later message on the conversation list rather than whichever write happened last.
        Query newer = Query.query(where("_id").is(conversationId).orOperator(
                where("lastMessageAt").is(null),
                where("lastMessageAt").lt(sentAt)));
        mongoTemplate.updateFirst(newer, new Update()
                .set("lastMessageContent", type == MessageType.VIDEO || type == MessageType.IMAGE ? null : content)
                .set("lastMessageType", type)
                .set("lastMessageSenderId", senderId)
                .set("lastMessageAt", sentAt)
                .unset("lastMessageRecalled")
                // Sending is not reading: what the sender has caught up on is untouched, and "unread"
                // for the other side is measured against this instead of against lastMessageAt.
                .max("lastSentAt." + senderId, sentAt)
                // Kept moving so a whole-document save elsewhere still sees that it is stale.
                .inc("version", 1), Conversation.class);
    }

    @Override
    public void markLastMessageRecalled(String conversationId, Long authorId, Instant sentAt) {
        Query isPreview = Query.query(where("_id").is(conversationId)
                .and("lastMessageAt").is(sentAt)
                .and("lastMessageSenderId").is(authorId));
        mongoTemplate.updateFirst(isPreview, new Update()
                .set("lastMessageRecalled", true)
                .set("lastMessageContent", null)
                .inc("version", 1), Conversation.class);
    }

    @Override
    public void markPreviewHiddenBy(String conversationId, Long userId) {
        mongoTemplate.updateFirst(Query.query(where("_id").is(conversationId)),
                new Update().addToSet("previewHiddenBy", userId).inc("version", 1), Conversation.class);
    }

    @Override
    public boolean acceptRequest(String conversationId, Long recipientId, Instant at) {
        return transition(pending(conversationId, recipientId), new Update().unset("requestState"), at);
    }

    @Override
    public boolean declineRequest(String conversationId, Long recipientId, Instant at) {
        return transition(pending(conversationId, recipientId),
                new Update().set("requestState", MessageRequestState.DECLINED), at);
    }

    @Override
    public boolean reopenDeclined(String conversationId, Long userId, Instant at) {
        Criteria declined = where("_id").is(conversationId).and("deletedAt").is(null)
                .and("requestState").is(MessageRequestState.DECLINED)
                .and("participantIds").is(userId)
                .and("initiatorId").ne(userId);
        return transition(declined, new Update().unset("requestState"), at);
    }

    @Override
    public boolean rependRequest(String conversationId, Long initiatorId, Instant at) {
        Criteria declined = where("_id").is(conversationId).and("deletedAt").is(null)
                .and("requestState").is(MessageRequestState.DECLINED)
                .and("initiatorId").is(initiatorId);
        return transition(declined, new Update().set("requestState", MessageRequestState.PENDING), at);
    }

    /** The conditional part is the filter: a second caller finds nothing matching and changes nothing. */
    private boolean transition(Criteria filter, Update change, Instant at) {
        return mongoTemplate.updateFirst(Query.query(filter),
                change.set("requestRespondedAt", at).inc("version", 1), Conversation.class)
                .getModifiedCount() > 0;
    }

    private static Criteria pending(String conversationId, Long recipientId) {
        Criteria criteria = where("_id").is(conversationId).and("deletedAt").is(null)
                .and("requestState").is(MessageRequestState.PENDING);
        return recipientId == null
                ? criteria
                : criteria.and("participantIds").is(recipientId).and("initiatorId").ne(recipientId);
    }

    @Override
    public List<Conversation> findInbox(Long userId) {
        Criteria criteria = where("participantIds").is(userId).and("deletedAt").is(null)
                .orOperator(where("requestState").is(null), where("initiatorId").is(userId));
        return mongoTemplate.find(newestFirst(criteria), Conversation.class);
    }

    @Override
    public List<Conversation> findIncomingRequests(Long userId) {
        Criteria criteria = where("participantIds").is(userId).and("deletedAt").is(null)
                .and("requestState").is(MessageRequestState.PENDING)
                .and("initiatorId").ne(userId)
                .and("lastMessageAt").ne(null);
        return mongoTemplate.find(newestFirst(criteria), Conversation.class);
    }

    private static Query newestFirst(Criteria criteria) {
        return Query.query(criteria).with(Sort.by(Sort.Direction.DESC, "lastMessageAt"));
    }

    @Override
    public void markRead(String conversationId, Long userId, Instant readAt) {
        mongoTemplate.updateFirst(Query.query(where("_id").is(conversationId)), new Update()
                .max("lastReadAt." + userId, readAt)
                .inc("version", 1), Conversation.class);
    }

    @Override
    public boolean advanceMark(String conversationId, Long userId, MarkKind kind, String messageId,
                               Instant sentAt, Instant at) {
        String field = kind.field() + "." + userId;
        // Absent, or at an earlier position: by time, and for messages sent in the same millisecond
        // by id — the order the thread itself is kept in.
        Criteria later = new Criteria().orOperator(
                where(field).exists(false),
                where(field).is(null),
                where(field + ".sentAt").lt(sentAt),
                new Criteria().andOperator(where(field + ".sentAt").is(sentAt), where(field + ".messageId").lt(messageId)));
        Update update = new Update().set(field, new ReceiptMark(messageId, sentAt, at)).inc("version", 1);
        if (kind == MarkKind.READ) {
            update.max("lastReadAt." + userId, sentAt);
        }
        return mongoTemplate.updateFirst(Query.query(where("_id").is(conversationId).andOperator(later)), update,
                Conversation.class).getModifiedCount() > 0;
    }
}
