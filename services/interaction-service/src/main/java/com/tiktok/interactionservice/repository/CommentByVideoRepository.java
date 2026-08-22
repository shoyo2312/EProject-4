package com.tiktok.interactionservice.repository;

import com.tiktok.interactionservice.entity.CommentByVideo;
import com.tiktok.interactionservice.entity.CommentByVideoKey;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface CommentByVideoRepository extends CassandraRepository<CommentByVideo, CommentByVideoKey> {

    @Query("SELECT * FROM comments_by_video WHERE video_id = :videoId")
    Slice<CommentByVideo> findByVideoId(@Param("videoId") Long videoId, Pageable pageable);

    /**
     * Lightweight-transaction soft delete. Returns whether this call is the one that deleted the
     * comment, which is the only thing that may drive the comment counter down: two concurrent
     * deletes of the same comment both pass the ownership read, and without the condition both
     * would decrement, leaving a video showing fewer comments than it has.
     */
    @Query("UPDATE comments_by_video SET deleted_at = :deletedAt "
            + "WHERE video_id = :videoId AND comment_id = :commentId IF deleted_at = null")
    boolean markDeletedIfNotDeleted(@Param("videoId") Long videoId,
                                    @Param("commentId") Long commentId,
                                    @Param("deletedAt") Instant deletedAt);
}
