package com.tiktok.interactionservice.repository;

import com.tiktok.interactionservice.entity.ViewByVideo;
import com.tiktok.interactionservice.entity.ViewByVideoKey;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface ViewByVideoRepository extends CassandraRepository<ViewByVideo, ViewByVideoKey> {

    /**
     * Lightweight-transaction insert carrying the dedup window as the row's TTL. Returns whether
     * the row was newly created, which is the only thing that may drive the view counter: the
     * same viewer replaying the same video, or a client retrying a request whose response it
     * never saw, gets false and counts nothing.
     *
     * <p>The TTL is applied to the row this insert writes, so each counted view starts its own
     * window rather than extending a shared one — a losing insert does not touch the existing
     * row's remaining TTL.
     */
    @Query("INSERT INTO views_by_video (video_id, user_id, viewed_at) VALUES (:videoId, :userId, :viewedAt) "
            + "IF NOT EXISTS USING TTL :ttlSeconds")
    boolean insertIfNotExists(@Param("videoId") Long videoId,
                              @Param("userId") Long userId,
                              @Param("viewedAt") Instant viewedAt,
                              @Param("ttlSeconds") int ttlSeconds);
}
