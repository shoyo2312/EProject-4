package com.tiktok.interactionservice.repository;

import com.tiktok.interactionservice.entity.CommentByOwner;
import com.tiktok.interactionservice.entity.CommentByOwnerKey;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.repository.query.Param;

public interface CommentByOwnerRepository extends CassandraRepository<CommentByOwner, CommentByOwnerKey> {

    /**
     * One owner's inbox, newest first — the table's own clustering order, one partition. Comment ids
     * are Snowflakes, so a range of ids is a range of time: a date filter is a clustering slice here,
     * not a scan of everything newer.
     */
    @Query("SELECT * FROM comments_by_owner WHERE owner_id = :ownerId AND comment_id >= :fromId AND comment_id < :toId")
    Slice<CommentByOwner> findNewestFirst(@Param("ownerId") Long ownerId, @Param("fromId") Long fromId,
                                          @Param("toId") Long toId, Pageable pageable);
}
