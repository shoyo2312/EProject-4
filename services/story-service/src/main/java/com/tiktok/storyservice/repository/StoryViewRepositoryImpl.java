package com.tiktok.storyservice.repository;

import com.tiktok.storyservice.entity.StoryReaction;
import com.tiktok.storyservice.entity.StoryView;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.group;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;
import static org.springframework.data.mongodb.core.query.Criteria.where;

/** Picked up by Spring Data through the {@code <Repository>Impl} naming convention. */
@RequiredArgsConstructor
public class StoryViewRepositoryImpl implements StoryViewRepositoryCustom {

    private final MongoTemplate mongoTemplate;

    private static Query viewOf(String storyId, Long viewerId) {
        return Query.query(where("storyId").is(storyId).and("viewerId").is(viewerId));
    }

    @Override
    public boolean upsertReaction(String storyId, Long viewerId, StoryReaction reaction, Instant expiresAt, Instant now) {
        Update update = new Update()
                .set("reaction", reaction)
                .set("reactedAt", now)
                .setOnInsert("viewedAt", now)
                .setOnInsert("expiresAt", expiresAt);
        return mongoTemplate.upsert(viewOf(storyId, viewerId), update, StoryView.class).getUpsertedId() != null;
    }

    @Override
    public void clearReaction(String storyId, Long viewerId) {
        mongoTemplate.updateFirst(viewOf(storyId, viewerId),
                new Update().unset("reaction").unset("reactedAt"), StoryView.class);
    }

    @Override
    public Map<StoryReaction, Long> countReactions(String storyId) {
        Aggregation aggregation = Aggregation.newAggregation(
                match(where("storyId").is(storyId).and("reaction").ne(null)),
                group("reaction").count().as("n"));
        Map<StoryReaction, Long> counts = new EnumMap<>(StoryReaction.class);
        for (Document row : mongoTemplate.aggregate(aggregation, StoryView.class, Document.class)) {
            counts.put(StoryReaction.valueOf(row.getString("_id")), ((Number) row.get("n")).longValue());
        }
        return counts;
    }
}
