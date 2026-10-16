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

    /**
     * The author of the reply this one is replying to, when that target was itself a reply — so the
     * flat list can show "A > B". Null for a top-level comment and for a direct reply to one (its
     * target is the thread's own top-level author, shown right above).
     */
    @Column("reply_to_user_id")
    private Long replyToUserId;

    /** Denormalised like tally; {@code null} until the first like. Read it through {@link #likeCount()}. */
    @Column("likes")
    private Integer likes;

    @Column("created_at")
    @CassandraType(type = CassandraType.Name.TIMESTAMP)
    private Instant createdAt;

    @Column("deleted_at")
    @CassandraType(type = CassandraType.Name.TIMESTAMP)
    private Instant deletedAt;

    /** Null on rows written before held comments existed — read it through {@link #effectiveStatus()}. */
    @Column("status")
    private CommentStatus status;

    /** Why the text rules held it: every flag, most severe first. Null for a comment nothing flagged. */
    @Column("mod_reason")
    private String modReason;

    /** Which rules held it, e.g. {@code rules-2026.10.13}. Null for a comment nothing flagged. */
    @Column("mod_version")
    private String modVersion;

    public static Long newId() {
        return SnowflakeIdGenerator.nextId();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public CommentStatus effectiveStatus() {
        return status == null ? CommentStatus.VISIBLE : status;
    }

    public boolean isHeld() {
        return effectiveStatus() == CommentStatus.HELD;
    }

    /** Not deleted and not held: a comment viewers may read, like and reply to. */
    public boolean isLive() {
        return !isDeleted() && !isHeld();
    }

    public int likeCount() {
        return likes == null ? 0 : likes;
    }
}
