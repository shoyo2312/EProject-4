package com.tiktok.interactionservice.repository;

import com.tiktok.interactionservice.entity.CommentLike;
import com.tiktok.interactionservice.entity.CommentLikeKey;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface CommentLikeRepository extends CassandraRepository<CommentLike, CommentLikeKey> {

    /**
     * Lightweight-transaction insert. Returns whether the row was newly created — the caller
     * moves the denormalised {@code comments_by_video.likes} count only when this is true, so a
     * retried like request cannot double-count.
     */
    @Query("INSERT INTO comment_likes (comment_id, user_id, created_at) "
            + "VALUES (:commentId, :userId, :createdAt) IF NOT EXISTS")
    boolean insertIfNotExists(@Param("commentId") Long commentId,
                              @Param("userId") Long userId,
                              @Param("createdAt") Instant createdAt);

    /**
     * Lightweight-transaction delete. Returns whether a row actually existed — the caller
     * decrements only when this is true.
     */
    @Query("DELETE FROM comment_likes WHERE comment_id = :commentId AND user_id = :userId IF EXISTS")
    boolean deleteIfExists(@Param("commentId") Long commentId, @Param("userId") Long userId);

    /**
     * Of {@code commentIds}, the ones this user has liked — one query per listing page to fill in
     * {@code likedByMe}. {@code IN} is on the partition key, so this stays a multi-partition point
     * read, not a scan.
     */
    @Query("SELECT comment_id FROM comment_likes WHERE comment_id IN :commentIds AND user_id = :userId")
    List<Long> findLikedCommentIds(@Param("commentIds") List<Long> commentIds,
                                   @Param("userId") Long userId);
}
