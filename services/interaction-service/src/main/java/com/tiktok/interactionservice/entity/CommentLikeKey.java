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
public class CommentLikeKey implements Serializable {

    @PrimaryKeyColumn(name = "comment_id", type = PrimaryKeyType.PARTITIONED)
    private Long commentId;

    @PrimaryKeyColumn(name = "user_id", type = PrimaryKeyType.CLUSTERED)
    private Long userId;
}
