package com.tiktok.interactionservice.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

/**
 * One id in one thread. Ids only: {@code comments_by_video} remains the source of truth for the
 * comment itself, so a like, an edit to the tally or a soft delete touches exactly one row and
 * this table can never disagree with it.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("comment_index")
public class CommentIndex {

    /**
     * Stands in for "no parent" in the partition key. A partition key component cannot be null,
     * and Snowflake never issues 0, so no comment can collide with it.
     */
    public static final long TOP_LEVEL = 0L;

    @PrimaryKey
    private CommentIndexKey key;

    public static CommentIndex of(Long videoId, Long parentId, Long commentId) {
        return CommentIndex.builder()
                .key(CommentIndexKey.builder()
                        .videoId(videoId)
                        .parentId(parentId == null ? TOP_LEVEL : parentId)
                        .commentId(commentId)
                        .build())
                .build();
    }
}
