package com.tiktok.chatservice.repository;

import com.tiktok.chatservice.entity.Conversation;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;

import static org.springframework.data.mongodb.core.query.Criteria.where;

@RequiredArgsConstructor
public class ConversationRepositoryImpl implements ConversationRepositoryCustom {

    private final MongoTemplate mongoTemplate;

    @Override
    public void recordMessage(String conversationId, Long senderId, String content, Instant sentAt) {
        // Conditional on the preview being older, so two sends landing out of order leave the
        // later message on the conversation list rather than whichever write happened last.
        Query newer = Query.query(where("_id").is(conversationId).orOperator(
                where("lastMessageAt").is(null),
                where("lastMessageAt").lt(sentAt)));
        mongoTemplate.updateFirst(newer, new Update()
                .set("lastMessageContent", content)
                .set("lastMessageSenderId", senderId)
                .set("lastMessageAt", sentAt)
                // Kept moving so a whole-document save elsewhere still sees that it is stale.
                .inc("version", 1), Conversation.class);

        markRead(conversationId, senderId, sentAt);
    }

    @Override
    public void markRead(String conversationId, Long userId, Instant readAt) {
        mongoTemplate.updateFirst(Query.query(where("_id").is(conversationId)), new Update()
                .max("lastReadAt." + userId, readAt)
                .inc("version", 1), Conversation.class);
    }
}
