package com.tiktok.interactionservice.repository;

import com.tiktok.interactionservice.entity.CommentByVideo;
import com.tiktok.interactionservice.entity.CommentByVideoKey;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.repository.query.Param;

public interface CommentByVideoRepository extends CassandraRepository<CommentByVideo, CommentByVideoKey> {

    @Query("SELECT * FROM comments_by_video WHERE video_id = :videoId")
    Slice<CommentByVideo> findByVideoId(@Param("videoId") Long videoId, Pageable pageable);
}
