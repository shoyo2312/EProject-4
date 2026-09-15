package com.tiktok.interactionservice.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

/** Per-comment counters. Only replies for now — Cassandra keeps counters in their own table. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("comment_counters")
public class CommentCounters {

    @PrimaryKey
    private CommentCountersKey key;

    /** Null until the first reply lands — read it through {@link #replies()}. */
    @Column("reply_count")
    private Long replyCount;

    public int replies() {
        return replyCount == null ? 0 : Math.max(0, replyCount.intValue());
    }
}
