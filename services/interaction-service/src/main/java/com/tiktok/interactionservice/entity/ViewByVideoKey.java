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
public class ViewByVideoKey implements Serializable {

    /**
     * Both columns partition, matching the table: nothing ever reads a video's viewers as a list,
     * and video_id alone would collect every viewer of a viral video into one partition that also
     * churns tombstones as each row's TTL expires. Ordinal is required once there are two
     * partition columns -- Cassandra hashes them in declaration order.
     */
    @PrimaryKeyColumn(name = "video_id", ordinal = 0, type = PrimaryKeyType.PARTITIONED)
    private Long videoId;

    @PrimaryKeyColumn(name = "user_id", ordinal = 1, type = PrimaryKeyType.PARTITIONED)
    private Long userId;
}
