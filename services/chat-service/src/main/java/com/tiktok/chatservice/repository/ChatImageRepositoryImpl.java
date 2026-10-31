package com.tiktok.chatservice.repository;

import com.tiktok.chatservice.entity.ChatImage;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.springframework.data.mongodb.core.query.Criteria.where;

@RequiredArgsConstructor
public class ChatImageRepositoryImpl implements ChatImageRepositoryCustom {

    /** How long an upload may wait for its message; the TTL monitor runs about once a minute. */
    public static final Duration UNCLAIMED_TTL = Duration.ofHours(1);

    private final MongoTemplate mongoTemplate;

    @Override
    public boolean claim(String conversationId, String imageId, Long uploaderId, String messageId) {
        Query query = Query.query(where("_id").is(imageId).and("conversationId").is(conversationId)
                .and("uploaderId").is(uploaderId).and("messageId").is(null));
        return mongoTemplate.updateFirst(query, new Update().set("messageId", messageId).unset("expiresAt"),
                ChatImage.class).getModifiedCount() > 0;
    }

    @Override
    public void release(String messageId) {
        mongoTemplate.updateFirst(Query.query(where("messageId").is(messageId)),
                new Update().unset("messageId").set("expiresAt", Instant.now().plus(UNCLAIMED_TTL)), ChatImage.class);
    }

    @Override
    public void deleteByMessageId(String messageId) {
        mongoTemplate.remove(Query.query(where("messageId").is(messageId)), ChatImage.class);
    }

    @Override
    public List<ChatImage> findMeta(String conversationId, Collection<String> imageIds) {
        Query query = Query.query(where("_id").in(imageIds).and("conversationId").is(conversationId));
        query.fields().exclude("data");
        return mongoTemplate.find(query, ChatImage.class);
    }

    @Override
    public Optional<ChatImage> findInConversation(String conversationId, String imageId) {
        return Optional.ofNullable(mongoTemplate.findOne(
                Query.query(where("_id").is(imageId).and("conversationId").is(conversationId)), ChatImage.class));
    }
}
