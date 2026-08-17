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
    public void updateTranscodeResult(Video video) {
        update(video.getId(), new Update()
                .set("status", video.getStatus())
                .set("statusBeforeTakedown", video.getStatusBeforeTakedown())
                .set("thumbnailUrl", video.getThumbnailUrl())
                .set("hlsUrl", video.getHlsUrl())
                .set("durationSeconds", video.getDurationSeconds()));
    }

    @Override
    public void updateStatus(Video video) {
        update(video.getId(), new Update()
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
     * updatedAt is written explicitly: {@code @LastModifiedDate} auditing only runs for
     * entity-based saves, so a field-scoped update would otherwise leave it stale.
     */
    private void update(String videoId, Update update) {
        mongoTemplate.updateFirst(
                Query.query(where("_id").is(videoId)),
                update.set("updatedAt", Instant.now()),
                Video.class);
    }
}
