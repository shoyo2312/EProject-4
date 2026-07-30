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
 * Reverse index of LikeByVideo: "videos liked by user X", kept in sync with likes_by_video
 * on every like/unlike write.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("likes_by_user")
public class LikeByUser {

    @PrimaryKey
    private LikeByUserKey key;

    @Column("created_at")
    @CassandraType(type = CassandraType.Name.TIMESTAMP)
    private Instant createdAt;
}
