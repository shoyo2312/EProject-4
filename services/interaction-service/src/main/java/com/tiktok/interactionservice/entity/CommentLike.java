package com.tiktok.interactionservice.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.cassandra.core.mapping.CassandraType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;

/**
 * Membership row: "user X liked comment Y". Existence of the row is the source of truth for
 * like status; toggling goes through lightweight-transaction CQL (IF NOT EXISTS / IF EXISTS),
 * not plain save/delete, so a retried request never moves the denormalised count twice.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("comment_likes")
public class CommentLike {

    @PrimaryKey
    private CommentLikeKey key;

    @Column("created_at")
    @CassandraType(type = CassandraType.Name.TIMESTAMP)
    private Instant createdAt;
}
