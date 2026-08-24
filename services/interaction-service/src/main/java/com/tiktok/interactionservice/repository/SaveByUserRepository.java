package com.tiktok.interactionservice.repository;

import com.tiktok.interactionservice.entity.SaveByUser;
import com.tiktok.interactionservice.entity.SaveByUserKey;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface SaveByUserRepository extends CassandraRepository<SaveByUser, SaveByUserKey> {

    /**
     * Lightweight-transaction insert, same contract as {@link LikeByVideoRepository#insertIfNotExists}:
     * true only for the call that actually created the row, so a retried save reports the state
     * back without repeating any work hung off it.
     */
    @Query("INSERT INTO saves_by_user (user_id, video_id, created_at) VALUES (:userId, :videoId, :createdAt) IF NOT EXISTS")
    boolean insertIfNotExists(@Param("userId") Long userId, @Param("videoId") Long videoId, @Param("createdAt") Instant createdAt);

    /** Lightweight-transaction delete. True only if a row was there to remove. */
    @Query("DELETE FROM saves_by_user WHERE user_id = :userId AND video_id = :videoId IF EXISTS")
    boolean deleteIfExists(@Param("userId") Long userId, @Param("videoId") Long videoId);

    @Query("SELECT * FROM saves_by_user WHERE user_id = :userId")
    Slice<SaveByUser> findByUserId(@Param("userId") Long userId, Pageable pageable);
}
