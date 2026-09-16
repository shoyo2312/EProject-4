package com.tiktok.interactionservice.repository;

import com.tiktok.interactionservice.entity.RepostByVideo;
import com.tiktok.interactionservice.entity.RepostByVideoKey;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface RepostByVideoRepository extends CassandraRepository<RepostByVideo, RepostByVideoKey> {

    /**
     * Lightweight-transaction insert. Returns whether the row was newly created — the caller
     * must only mutate the repost counter when this is true, otherwise a retried/duplicate repost
     * would double-count.
     */
    @Query("INSERT INTO reposts_by_video (video_id, user_id, created_at) VALUES (:videoId, :userId, :createdAt) IF NOT EXISTS")
    boolean insertIfNotExists(@Param("videoId") Long videoId, @Param("userId") Long userId, @Param("createdAt") Instant createdAt);

    /** Lightweight-transaction delete. True only if a row was there to remove. */
    @Query("DELETE FROM reposts_by_video WHERE video_id = :videoId AND user_id = :userId IF EXISTS")
    boolean deleteIfExists(@Param("videoId") Long videoId, @Param("userId") Long userId);

    /**
     * Who has reposted this video, capped, for the repost-badge lookup. No time ordering —
     * user_id is the clustering key, not created_at — so this is "up to `limit` reposters",
     * not "the most recent ones". Fine for the badge: it only needs to find one match against
     * the viewer's following set, not the newest one.
     */
    @Query("SELECT * FROM reposts_by_video WHERE video_id = :videoId LIMIT :limit")
    List<RepostByVideo> findByVideoId(@Param("videoId") Long videoId, @Param("limit") int limit);
}
