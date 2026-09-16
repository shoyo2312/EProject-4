package com.tiktok.interactionservice.repository;

import com.tiktok.interactionservice.entity.CommentCounters;
import com.tiktok.interactionservice.entity.CommentCountersKey;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CommentCountersRepository extends CassandraRepository<CommentCounters, CommentCountersKey> {

    @Query("UPDATE comment_counters SET reply_count = reply_count + :delta "
            + "WHERE video_id = :videoId AND comment_id = :commentId")
    void incrementReplyCount(@Param("videoId") Long videoId,
                             @Param("commentId") Long commentId,
                             @Param("delta") long delta);

    /**
     * The reply tallies for one page of comments in a single read. {@code video_id} is fixed and
     * the {@code IN} covers only the second half of the partition key, so this is a bounded
     * multi-partition read over one video, not a scan.
     */
    @Query("SELECT * FROM comment_counters WHERE video_id = :videoId AND comment_id IN :commentIds")
    List<CommentCounters> findAllByCommentIds(@Param("videoId") Long videoId,
                                              @Param("commentIds") List<Long> commentIds);
}
