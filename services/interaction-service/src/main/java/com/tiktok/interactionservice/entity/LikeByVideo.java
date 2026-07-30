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
 * Membership row: "user X liked video Y". Existence of the row is the source of truth for
 * like status; like/unlike toggling is done via lightweight-transaction (IF NOT EXISTS /
 * IF EXISTS) raw CQL, not plain save/delete, so counters never double-count on retries.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("likes_by_video")
public class LikeByVideo {

    @PrimaryKey
    private LikeByVideoKey key;

    @Column("created_at")
    @CassandraType(type = CassandraType.Name.TIMESTAMP)
    private Instant createdAt;
}
