package com.tiktok.interactionservice.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyClass;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;

import java.io.Serializable;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@PrimaryKeyClass
public class CommentIndexKey implements Serializable {

    @PrimaryKeyColumn(name = "video_id", type = PrimaryKeyType.PARTITIONED, ordinal = 0)
    private Long videoId;

    /**
     * The thread this id belongs to: {@link CommentIndex#TOP_LEVEL} for a top-level comment,
     * otherwise the top-level comment it replies to. Part of the partition key, which is the whole
     * point of this table — it turns both listings into a single-partition read.
     */
    @PrimaryKeyColumn(name = "parent_id", type = PrimaryKeyType.PARTITIONED, ordinal = 1)
    private Long parentId;

    @PrimaryKeyColumn(name = "comment_id", type = PrimaryKeyType.CLUSTERED, ordering = Ordering.ASCENDING)
    private Long commentId;
}
