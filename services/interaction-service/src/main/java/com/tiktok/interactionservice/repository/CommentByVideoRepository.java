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
     * Writes the denormalised per-comment like tally, conditioned on the value the caller read.
     * The {@code comment_likes} membership LWT grants the right to move the count, but it does not
     * serialise the read-modify-write around it: two different users liking the same comment both
     * pass their own membership check, both read 5, and both write 6. Nothing reconciles this
     * tally afterwards, so a lost increment is permanent.
     *
     * @return false when the stored value is no longer {@code expected} — re-read and re-apply
     */
    @Query("UPDATE comments_by_video SET likes = :likes "
            + "WHERE video_id = :videoId AND comment_id = :commentId IF likes = :expected")
    boolean updateLikesIfMatches(@Param("videoId") Long videoId,
                                 @Param("commentId") Long commentId,
                                 @Param("likes") int likes,
                                 @Param("expected") int expected);

    /**
     * The same write for a comment nobody has liked yet, whose {@code likes} column is still
     * unset. A separate method because the condition is against {@code null}, which has to be a
     * literal in the query rather than a bound value — same shape as
     * {@link #markDeletedIfNotDeleted}.
     */
    @Query("UPDATE comments_by_video SET likes = :likes "
            + "WHERE video_id = :videoId AND comment_id = :commentId IF likes = null")
    boolean initLikesIfUnset(@Param("videoId") Long videoId,
                             @Param("commentId") Long commentId,
                             @Param("likes") int likes);

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

    /**
     * Undoes one {@link #markDeletedIfNotDeleted} whose counter decrement never landed. Conditioned
     * on the timestamp that call wrote, so it only ever reverses <em>that</em> deletion: another
     * request deleting the same comment in between leaves a different value there, and this update
     * then matches nothing rather than resurrecting a comment somebody meant to remove.
     */
    @Query("UPDATE comments_by_video SET deleted_at = null "
            + "WHERE video_id = :videoId AND comment_id = :commentId IF deleted_at = :deletedAt")
    boolean restoreIfDeletedAt(@Param("videoId") Long videoId,
                               @Param("commentId") Long commentId,
                               @Param("deletedAt") Instant deletedAt);
}
