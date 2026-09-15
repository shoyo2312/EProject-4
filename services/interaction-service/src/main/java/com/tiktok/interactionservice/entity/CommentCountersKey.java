package com.tiktok.interactionservice.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyClass;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;

import java.io.Serializable;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@PrimaryKeyClass
public class CommentCountersKey implements Serializable {

    @PrimaryKeyColumn(name = "video_id", type = PrimaryKeyType.PARTITIONED, ordinal = 0)
    private Long videoId;

    @PrimaryKeyColumn(name = "comment_id", type = PrimaryKeyType.PARTITIONED, ordinal = 1)
    private Long commentId;
}
