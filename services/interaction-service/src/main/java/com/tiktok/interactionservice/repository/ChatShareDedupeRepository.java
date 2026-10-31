package com.tiktok.interactionservice.repository;

import com.tiktok.interactionservice.entity.ChatShareDedupe;
import com.tiktok.interactionservice.entity.ChatShareDedupeKey;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatShareDedupeRepository extends CassandraRepository<ChatShareDedupe, ChatShareDedupeKey> {
    @Query("INSERT INTO chat_share_dedupe (video_id, dedupe_key, share_id) VALUES (:videoId, :key, :shareId) IF NOT EXISTS USING TTL 2592000")
    boolean claim(@Param("videoId") Long videoId, @Param("key") String key, @Param("shareId") Long shareId);
}
