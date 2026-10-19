package com.tiktok.interactionservice.repository;

import com.tiktok.interactionservice.entity.HeldComment;
import com.tiktok.interactionservice.entity.HeldCommentKey;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.repository.query.Param;

public interface HeldCommentRepository extends CassandraRepository<HeldComment, HeldCommentKey> {

    /** One video's queue, newest first — the table's own clustering order, one partition. */
    @Query("SELECT * FROM held_comments_by_video WHERE video_id = :videoId")
    Slice<HeldComment> findNewestFirst(@Param("videoId") Long videoId, Pageable pageable);
}
