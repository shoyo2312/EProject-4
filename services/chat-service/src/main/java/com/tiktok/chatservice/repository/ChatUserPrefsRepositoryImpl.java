package com.tiktok.chatservice.repository;

import com.tiktok.chatservice.entity.ChatUserPrefs;
import com.tiktok.chatservice.entity.MuteMode;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import static org.springframework.data.mongodb.core.query.Criteria.where;

@RequiredArgsConstructor
public class ChatUserPrefsRepositoryImpl implements ChatUserPrefsRepositoryCustom {

    /** A lost race against an unpin can look like "full" for one attempt; the second look settles it. */
    private static final int PIN_ATTEMPTS = 3;

    private final MongoTemplate mongoTemplate;

    @Override
    public PinOutcome pin(Long userId, String conversationId, Instant at) {
        for (int attempt = 0; attempt < PIN_ATTEMPTS; attempt++) {
            ChatUserPrefs prefs = mongoTemplate.findById(userId, ChatUserPrefs.class);
            if (prefs != null && prefs.pinnedAt(conversationId) != null) {
                return PinOutcome.ALREADY_PINNED;
            }
            if (prefs == null) {
                // The document the conditional update below needs to match. A concurrent first call
                // for the same user is a duplicate _id the server resolves; both end up with one document.
                mongoTemplate.upsert(Query.query(where("_id").is(userId)),
                        new Update().setOnInsert("pins", List.of()), ChatUserPrefs.class);
            }

            Instant placed = laterThanEveryPin(prefs, at);
            Criteria open = where("_id").is(userId)
                    .and("pins." + (ChatUserPrefs.MAX_PINS - 1)).exists(false)
                    .and("pins.conversationId").ne(conversationId);
            Update push = new Update().push("pins",
                    new Document("conversationId", conversationId).append("pinnedAt", Date.from(placed)));
            if (mongoTemplate.updateFirst(Query.query(open), push, ChatUserPrefs.class).getModifiedCount() > 0) {
                return PinOutcome.PINNED;
            }

            ChatUserPrefs after = mongoTemplate.findById(userId, ChatUserPrefs.class);
            if (after != null && after.pinnedAt(conversationId) != null) {
                return PinOutcome.ALREADY_PINNED;
            }
            if (after != null && after.getPins().size() >= ChatUserPrefs.MAX_PINS) {
                return PinOutcome.FULL;
            }
        }
        return PinOutcome.FULL;
    }

    private static Instant laterThanEveryPin(ChatUserPrefs prefs, Instant at) {
        if (prefs == null || prefs.getPins() == null) {
            return at;
        }
        return prefs.getPins().stream().map(ChatUserPrefs.Pin::pinnedAt)
                .filter(existing -> !existing.isBefore(at))
                .max(Instant::compareTo)
                .map(newest -> newest.plus(1, ChronoUnit.MILLIS))
                .orElse(at);
    }

    @Override
    public void unpin(Long userId, String conversationId) {
        mongoTemplate.updateFirst(Query.query(where("_id").is(userId)),
                new Update().pull("pins", new Document("conversationId", conversationId)), ChatUserPrefs.class);
    }

    @Override
    public void mute(Long userId, String conversationId, MuteMode mode, Instant until) {
        Document mute = new Document("mode", mode.name())
                .append("until", mode == MuteMode.TIMED ? Date.from(until) : null);
        mongoTemplate.upsert(Query.query(where("_id").is(userId)),
                new Update().set("mutes." + conversationId, mute), ChatUserPrefs.class);
    }

    @Override
    public void unmute(Long userId, String conversationId) {
        mongoTemplate.updateFirst(Query.query(where("_id").is(userId)),
                new Update().unset("mutes." + conversationId), ChatUserPrefs.class);
    }

    @Override
    public void clear(Long userId, String conversationId, Instant at) {
        mongoTemplate.upsert(Query.query(where("_id").is(userId)),
                new Update().max("clearedAt." + conversationId, at)
                        .pull("pins", new Document("conversationId", conversationId)),
                ChatUserPrefs.class);
    }
}
