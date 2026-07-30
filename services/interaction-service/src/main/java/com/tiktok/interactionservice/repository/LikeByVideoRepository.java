package com.tiktok.interactionservice.repository;

import com.tiktok.interactionservice.entity.LikeByVideo;
import com.tiktok.interactionservice.entity.LikeByVideoKey;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface LikeByVideoRepository extends CassandraRepository<LikeByVideo, LikeByVideoKey> {

    /**
     * Lightweight-transaction insert. Returns whether the row was newly created — the
     * caller must only mutate the like counter when this is true, otherwise a retried/
     * duplicate like request would double-count.
     */
    @Query("INSERT INTO likes_by_video (video_id, user_id, created_at) VALUES (:videoId, :userId, :createdAt) IF NOT EXISTS")
    boolean insertIfNotExists(@Param("videoId") Long videoId, @Param("userId") Long userId, @Param("createdAt") Instant createdAt);

    /**
     * Lightweight-transaction delete. Returns whether a row actually existed — the caller
     * must only decrement the counter when this is true.
     */
    @Query("DELETE FROM likes_by_video WHERE video_id = :videoId AND user_id = :userId IF EXISTS")
    boolean deleteIfExists(@Param("videoId") Long videoId, @Param("userId") Long userId);
}
