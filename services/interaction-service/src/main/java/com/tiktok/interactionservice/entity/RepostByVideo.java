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
 * Membership row: "user X reposted video Y". Existence of the row is the source of truth for
 * repost status — repost/un-repost toggles via lightweight-transaction (IF NOT EXISTS / IF
 * EXISTS), same as {@link LikeByVideo}, not plain save/delete, so the counter never double-counts
 * on a retry. Also the reverse index the repost badge reads to answer "who reposted this video".
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("reposts_by_video")
public class RepostByVideo {

    @PrimaryKey
    private RepostByVideoKey key;

    @Column("created_at")
    @CassandraType(type = CassandraType.Name.TIMESTAMP)
    private Instant createdAt;
}
