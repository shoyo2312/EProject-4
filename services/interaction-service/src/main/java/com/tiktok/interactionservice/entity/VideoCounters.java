package com.tiktok.interactionservice.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

/**
 * Counter columns must live in a table containing only counters + the partition key
 * (Cassandra restriction) — reads only, mutations go through custom counter UPDATE
 * queries in VideoCountersRepository, never a plain save().
 *
 * <p>Counter columns are independently nullable: a row can exist (because one counter
 * was incremented) while a sibling counter column was never touched and reads back as
 * null — hence boxed {@code Long}, not primitive {@code long}.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("video_counters")
public class VideoCounters {

    @PrimaryKey("video_id")
    private Long videoId;

    @Column("like_count")
    private Long likeCount;

    @Column("comment_count")
    private Long commentCount;

    @Column("share_count")
    private Long shareCount;

    @Column("view_count")
    private Long viewCount;
}
