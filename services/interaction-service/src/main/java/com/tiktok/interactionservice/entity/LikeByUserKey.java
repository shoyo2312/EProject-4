package com.tiktok.interactionservice.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.CassandraType;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyClass;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;

import java.io.Serializable;
import java.time.Instant;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@PrimaryKeyClass
public class LikeByUserKey implements Serializable {

    @PrimaryKeyColumn(name = "user_id", type = PrimaryKeyType.PARTITIONED)
    private Long userId;

    /**
     * Part of the key, not a plain column: it is what "videos I liked" is ordered by, and what
     * unlike needs in order to address the row it must delete. video_id is a Snowflake, so
     * clustering on it would order the listing by when each video was made instead.
     */
    @PrimaryKeyColumn(name = "created_at", ordinal = 0, type = PrimaryKeyType.CLUSTERED, ordering = Ordering.DESCENDING)
    @CassandraType(type = CassandraType.Name.TIMESTAMP)
    private Instant createdAt;

    /** Tie-breaker only, so two likes in the same millisecond stay two rows. */
    @PrimaryKeyColumn(name = "video_id", ordinal = 1, type = PrimaryKeyType.CLUSTERED, ordering = Ordering.ASCENDING)
    private Long videoId;
}
