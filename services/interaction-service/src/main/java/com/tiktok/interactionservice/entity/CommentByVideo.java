package com.tiktok.interactionservice.entity;

import com.tiktok.common.id.SnowflakeIdGenerator;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.cassandra.core.mapping.CassandraType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("comments_by_video")
public class CommentByVideo {

    @PrimaryKey
    private CommentByVideoKey key;

    @Column("user_id")
    private Long userId;

    @Column("content")
    private String content;

    /**
     * Null for a top-level comment; the top-level comment's id for a reply. TikTok nests exactly
     * one level, so this never points at another reply — {@code CommentServiceImpl} flattens a
     * reply-to-a-reply back to its top-level ancestor before saving.
     */
    @Column("parent_id")
    private Long parentId;

    /** Denormalised like tally; {@code null} until the first like. Read it through {@link #likeCount()}. */
    @Column("likes")
    private Integer likes;

    @Column("created_at")
    @CassandraType(type = CassandraType.Name.TIMESTAMP)
    private Instant createdAt;

    @Column("deleted_at")
    @CassandraType(type = CassandraType.Name.TIMESTAMP)
    private Instant deletedAt;

    public static Long newId() {
        return SnowflakeIdGenerator.nextId();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public int likeCount() {
        return likes == null ? 0 : likes;
    }
}
