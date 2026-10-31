package com.tiktok.chatservice.repository;

import com.tiktok.chatservice.entity.Message;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.springframework.data.mongodb.core.query.Criteria.where;

@RequiredArgsConstructor
public class MessageRepositoryImpl implements MessageRepositoryCustom {

    private final MongoTemplate mongoTemplate;

    @Override
    public long clearStaleRequestFlag(String conversationId, Instant olderThan) {
        Query stale = Query.query(where("conversationId").is(conversationId)
                .and("requestMessage").is(true)
                .and("sentAt").lte(olderThan));
        return mongoTemplate.updateMulti(stale, new Update().unset("requestMessage"), Message.class)
                .getModifiedCount();
    }

    @Override
    public List<Message> findOthersBetween(String conversationId, Long excludedSender, Instant afterSentAt,
                                           String afterId, Instant throughSentAt, String throughId, int limit) {
        List<Criteria> all = new ArrayList<>();
        all.add(where("conversationId").is(conversationId));
        all.add(where("deletedAt").is(null));
        all.add(where("senderId").ne(excludedSender));
        if (afterSentAt != null && afterId != null) {
            all.add(new Criteria().orOperator(
                    where("sentAt").gt(afterSentAt),
                    where("sentAt").is(afterSentAt).and("_id").gt(afterId)));
        }
        all.add(new Criteria().orOperator(
                where("sentAt").lt(throughSentAt),
                where("sentAt").is(throughSentAt).and("_id").lte(throughId)));
        Query query = Query.query(new Criteria().andOperator(all))
                .with(Sort.by(Sort.Direction.ASC, "sentAt", "_id"))
                .limit(limit);
        return mongoTemplate.find(query, Message.class);
    }

    @Override
    public Optional<Message> findNewestOthers(String conversationId, Long excludedSender) {
        Query query = Query.query(where("conversationId").is(conversationId).and("deletedAt").is(null)
                        .and("senderId").ne(excludedSender))
                .with(Sort.by(Sort.Direction.DESC, "sentAt", "_id"))
                .limit(1);
        return Optional.ofNullable(mongoTemplate.findOne(query, Message.class));
    }

    @Override
    public List<Message> findPage(String conversationId, Long viewerId, Instant visibleAfter, Instant beforeSentAt,
                                  String beforeId, int limit) {
        // ne on an array is "does not contain" — and true when the field is missing, which is every
        // message stored before hiding existed.
        Criteria criteria = where("conversationId").is(conversationId).and("deletedAt").is(null)
                .and("hiddenFor").ne(viewerId);
        List<Criteria> alsoOnSentAt = new ArrayList<>();
        if (visibleAfter != null) {
            alsoOnSentAt.add(where("sentAt").gt(visibleAfter));
        }
        if (beforeSentAt != null && beforeId != null) {
            // The keyset: everything strictly older, plus the messages sharing the cursor's
            // millisecond that sort after it. Without the second half those are skipped — see
            // MessageCursor.
            criteria = criteria.orOperator(
                    where("sentAt").lt(beforeSentAt),
                    where("sentAt").is(beforeSentAt).and("_id").lt(beforeId));
        } else if (beforeSentAt != null) {
            alsoOnSentAt.add(where("sentAt").lt(beforeSentAt));
        }
        if (!alsoOnSentAt.isEmpty()) {
            criteria = criteria.andOperator(alsoOnSentAt);
        }

        // The same order as conversation_sent_id_idx, _id included, so Mongo walks the index
        // instead of sorting everything older than the cursor in memory for every page.
        Query query = Query.query(criteria)
                .with(Sort.by(Sort.Direction.DESC, "sentAt", "_id"))
                .limit(limit);
        return mongoTemplate.find(query, Message.class);
    }

    @Override
    public Optional<Message> findInConversation(String conversationId, String messageId) {
        return Optional.ofNullable(mongoTemplate.findOne(
                Query.query(where("_id").is(messageId).and("conversationId").is(conversationId).and("deletedAt").is(null)),
                Message.class));
    }

    @Override
    public List<Message> findInConversation(String conversationId, Collection<String> messageIds) {
        if (messageIds.isEmpty()) {
            return List.of();
        }
        return mongoTemplate.find(
                Query.query(where("_id").in(messageIds).and("conversationId").is(conversationId).and("deletedAt").is(null)),
                Message.class);
    }

    @Override
    public boolean setReaction(String conversationId, String messageId, Long userId, String emoji) {
        String slot = "reactions." + userId;
        Query query = Query.query(where("_id").is(messageId).and("conversationId").is(conversationId)
                .and("deletedAt").is(null)
                .and("recalledAt").is(null)
                .and(slot).ne(emoji));
        return mongoTemplate.updateFirst(query, new Update().set(slot, emoji).inc("revision", 1), Message.class)
                .getModifiedCount() > 0;
    }

    @Override
    public boolean clearReaction(String conversationId, String messageId, Long userId) {
        String slot = "reactions." + userId;
        Query query = Query.query(where("_id").is(messageId).and("conversationId").is(conversationId)
                .and(slot).exists(true));
        return mongoTemplate.updateFirst(query, new Update().unset(slot).inc("revision", 1), Message.class)
                .getModifiedCount() > 0;
    }

    @Override
    public boolean recall(String conversationId, String messageId, Long authorId, Instant at) {
        Query query = Query.query(where("_id").is(messageId).and("conversationId").is(conversationId)
                .and("senderId").is(authorId)
                .and("recalledAt").is(null));
        Update update = new Update()
                .set("recalledAt", at)
                .unset("content")
                .unset("stickerId")
                .unset("videoId")
                .unset("imageId")
                .unset("storyId")
                .unset("reactions")
                .inc("revision", 1);
        return mongoTemplate.updateFirst(query, update, Message.class).getModifiedCount() > 0;
    }

    @Override
    public void hide(String conversationId, String messageId, Long userId) {
        Query query = Query.query(where("_id").is(messageId).and("conversationId").is(conversationId));
        mongoTemplate.updateFirst(query, new Update().addToSet("hiddenFor", userId), Message.class);
    }
}
