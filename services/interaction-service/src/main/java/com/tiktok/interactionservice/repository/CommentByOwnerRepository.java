package com.tiktok.interactionservice.repository;

import com.tiktok.interactionservice.entity.CommentByOwner;
import com.tiktok.interactionservice.entity.CommentByOwnerKey;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.repository.query.Param;

public interface CommentByOwnerRepository extends CassandraRepository<CommentByOwner, CommentByOwnerKey> {

    /** One owner's inbox, newest first — the table's own clustering order, one partition. */
    @Query("SELECT * FROM comments_by_owner WHERE owner_id = :ownerId")
    Slice<CommentByOwner> findNewestFirst(@Param("ownerId") Long ownerId, Pageable pageable);
}
