package com.tiktok.chatservice.repository;

import com.tiktok.chatservice.entity.Message;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.List;

import static org.springframework.data.mongodb.core.query.Criteria.where;

@RequiredArgsConstructor
public class MessageRepositoryImpl implements MessageRepositoryCustom {

    private final MongoTemplate mongoTemplate;

    @Override
    public List<Message> findPage(String conversationId, Instant beforeSentAt, String beforeId, int limit) {
        Criteria criteria = where("conversationId").is(conversationId).and("deletedAt").is(null);
        if (beforeSentAt != null && beforeId != null) {
            // The keyset: everything strictly older, plus the messages sharing the cursor's
            // millisecond that sort after it. Without the second half those are skipped — see
            // MessageCursor.
            criteria = criteria.orOperator(
                    where("sentAt").lt(beforeSentAt),
                    where("sentAt").is(beforeSentAt).and("_id").lt(beforeId));
        } else if (beforeSentAt != null) {
            criteria = criteria.and("sentAt").lt(beforeSentAt);
        }

        // The same order as conversation_sent_id_idx, _id included, so Mongo walks the index
        // instead of sorting everything older than the cursor in memory for every page.
        Query query = Query.query(criteria)
                .with(Sort.by(Sort.Direction.DESC, "sentAt", "_id"))
                .limit(limit);
        return mongoTemplate.find(query, Message.class);
    }
}
