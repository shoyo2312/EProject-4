package com.tiktok.interactionservice.repository;

import com.tiktok.interactionservice.entity.VideoCounters;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VideoCountersRepository extends CassandraRepository<VideoCounters, Long> {

    @Query("UPDATE video_counters SET like_count = like_count + :delta WHERE video_id = :videoId")
    void incrementLikeCount(@Param("videoId") Long videoId, @Param("delta") long delta);

    @Query("UPDATE video_counters SET comment_count = comment_count + :delta WHERE video_id = :videoId")
    void incrementCommentCount(@Param("videoId") Long videoId, @Param("delta") long delta);

    @Query("UPDATE video_counters SET share_count = share_count + :delta WHERE video_id = :videoId")
    void incrementShareCount(@Param("videoId") Long videoId, @Param("delta") long delta);
}
