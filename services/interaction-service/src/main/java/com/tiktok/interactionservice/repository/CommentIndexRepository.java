package com.tiktok.interactionservice.repository;

import com.tiktok.interactionservice.entity.CommentIndex;
import com.tiktok.interactionservice.entity.CommentIndexKey;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CommentIndexRepository extends CassandraRepository<CommentIndex, CommentIndexKey> {

    /**
     * One thread's ids newest first — the top-level listing, with
     * {@code parentId} = {@link CommentIndex#TOP_LEVEL}.
     *
     * <p>The table clusters ascending because replies are read in the order they were written;
     * this asks for the reverse, which Cassandra serves from the same partition. No
     * {@code ALLOW FILTERING}: the thread is the partition.
     */
    @Query("SELECT * FROM comment_index WHERE video_id = :videoId AND parent_id = :parentId "
            + "ORDER BY comment_id DESC")
    Slice<CommentIndex> findNewestFirst(@Param("videoId") Long videoId,
                                        @Param("parentId") Long parentId,
                                        Pageable pageable);

    /**
     * Which of these comments have at least one reply — one row per thread that does, none for a
     * thread that does not. {@code PER PARTITION LIMIT 1} is what keeps it bounded: the {@code IN}
     * covers only the second half of the partition key with the video fixed, and each partition
     * contributes a single row however many replies it holds.
     *
     * <p>Answers the question {@code comment_counters} cannot. That tally is decremented when a
     * reply is removed, so a thread whose replies were all taken down reads 0 there; the index row
     * behind a removed reply stays, so it reads true here — and the console keeps offering the
     * "View replies" that gets a moderator to them.
     */
    @Query("SELECT * FROM comment_index WHERE video_id = :videoId AND parent_id IN :parentIds "
            + "PER PARTITION LIMIT 1")
    List<CommentIndex> findThreadsWithReplies(@Param("videoId") Long videoId,
                                              @Param("parentIds") List<Long> parentIds);

    /** One comment's replies oldest first, which is the order a thread reads in. */
    @Query("SELECT * FROM comment_index WHERE video_id = :videoId AND parent_id = :parentId "
            + "ORDER BY comment_id ASC")
    Slice<CommentIndex> findOldestFirst(@Param("videoId") Long videoId,
                                        @Param("parentId") Long parentId,
                                        Pageable pageable);
}
