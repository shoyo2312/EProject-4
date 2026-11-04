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
public class CommentByOwnerKey implements Serializable {

    @PrimaryKeyColumn(name = "owner_id", type = PrimaryKeyType.PARTITIONED)
    private Long ownerId;

    /** Snowflake, so descending is newest first across all of the owner's videos. */
    @PrimaryKeyColumn(name = "comment_id", type = PrimaryKeyType.CLUSTERED, ordering = Ordering.DESCENDING)
    private Long commentId;
}
