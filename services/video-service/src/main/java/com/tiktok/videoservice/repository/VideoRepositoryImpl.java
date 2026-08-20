package com.tiktok.videoservice.repository;

import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.List;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * See {@link VideoRepositoryCustom} for why these are field-scoped updates rather than
 * {@code save()}. Picked up by Spring Data through the {@code <Repository>Impl} naming
 * convention, same as {@link ProcessedEventRepositoryImpl}.
 */
@RequiredArgsConstructor
public class VideoRepositoryImpl implements VideoRepositoryCustom {

    private final MongoTemplate mongoTemplate;

    @Override
    public List<Video> findFeedPage(Instant beforeCreatedAt, String beforeId, int limit) {
        Criteria criteria = where("status").is(VideoStatus.PUBLISHED)
                .and("visibility").is(VideoVisibility.PUBLIC)
                .and("deletedAt").is(null);

        if (beforeCreatedAt != null) {
            // The OR is the keyset: everything strictly older, plus the rows sharing the cursor's
            // millisecond that sort after it. See FeedCursor for why the second half is not
            // optional. andOperator rather than more .and() calls — Criteria.and() on a field
            // already constrained above would overwrite it silently.
            criteria = criteria.andOperator(new Criteria().orOperator(
                    where("createdAt").lt(beforeCreatedAt),
                    where("createdAt").is(beforeCreatedAt).and("_id").lt(beforeId)));
        }

        // Sort order matches feed_idx exactly, including _id, so Mongo takes the ordering from the
        // index instead of sorting the match set in memory.
        Query query = Query.query(criteria)
                .with(Sort.by(Sort.Direction.DESC, "createdAt", "_id"))
                .limit(limit);

        return mongoTemplate.find(query, Video.class);
    }

    // statusBeforeTakedown is part of both transcode writes because that is where the outcome
    // goes while the video is down — see Video.applyTranscodeOutcome. Leaving it out would drop
    // the only record of the result on exactly the videos that need it at restore time.
    @Override
    public boolean updateTranscodeResult(Video video, VideoStatus expectedStatus) {
        return compareAndSet(video.getId(), expectedStatus, new Update()
                .set("status", video.getStatus())
                .set("statusBeforeTakedown", video.getStatusBeforeTakedown())
                .set("thumbnailUrl", video.getThumbnailUrl())
                .set("hlsUrl", video.getHlsUrl())
                .set("durationSeconds", video.getDurationSeconds()));
    }

    @Override
    public boolean updateStatus(Video video, VideoStatus expectedStatus) {
        return compareAndSet(video.getId(), expectedStatus, new Update()
                .set("status", video.getStatus())
                .set("statusBeforeTakedown", video.getStatusBeforeTakedown()));
    }

    @Override
    public void updateEventPublished(Video video) {
        update(video.getId(), new Update().set("eventPublishedAt", video.getEventPublishedAt()));
    }

    @Override
    public void updateSoftDeleted(Video video) {
        update(video.getId(), new Update().set("deletedAt", video.getDeletedAt()));
    }

    /**
     * The status the caller read is part of the filter, so a write only lands if nothing else
     * moved the video in between. Both callers are Kafka consumers on different topics, running
     * on different listener threads: a transcode result that takes minutes to arrive is routinely
     * overtaken by a moderator, and without this filter the later write wins by arriving last
     * rather than by being correct — a takedown silently replaced by PUBLISHED.
     *
     * <p>The filter is on status rather than on {@code @Version} on purpose. Spring Data does bump
     * the version on these field-scoped updates, so a version filter would also fail whenever a
     * like landed concurrently — the exact competition {@link VideoRepositoryCustom} was shaped to
     * remove. Status is the field being contended; a like moving likeCount is not a conflict.
     *
     * @return false when nothing matched, meaning the status changed underneath: the caller must
     *         re-read and re-apply against the new state, never simply write again
     */
    private boolean compareAndSet(String videoId, VideoStatus expectedStatus, Update update) {
        // deletedAt belongs in the filter alongside status. VideoStateUpdater checks it when it
        // reads, but a delete landing between that read and this write would still match on
        // _id and status alone, producing the deleted-and-PUBLISHED document that class says it
        // prevents. Transcoding runs for minutes, so the gap is not a theoretical one. Failing
        // here is harmless: a false return sends the caller back to re-read, and the re-read
        // finds the video deleted and stops.
        Query query = Query.query(
                where("_id").is(videoId).and("status").is(expectedStatus).and("deletedAt").is(null));
        return write(query, update) > 0;
    }

    private void update(String videoId, Update update) {
        write(Query.query(where("_id").is(videoId)), update);
    }

    /**
     * updatedAt is written explicitly: {@code @LastModifiedDate} auditing only runs for
     * entity-based saves, so a field-scoped update would otherwise leave it stale.
     */
    private long write(Query query, Update update) {
        return mongoTemplate.updateFirst(query, update.set("updatedAt", Instant.now()), Video.class)
                .getMatchedCount();
    }
}
