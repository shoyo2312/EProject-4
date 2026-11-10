package com.tiktok.interactionservice.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyClass;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import java.io.Serializable;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@PrimaryKeyClass
public class ChatShareDedupeKey implements Serializable {
    @PrimaryKeyColumn(name = "video_id", ordinal = 0, type = PrimaryKeyType.PARTITIONED)
    private Long videoId;
    @PrimaryKeyColumn(name = "dedupe_key", ordinal = 1, type = PrimaryKeyType.PARTITIONED)
    private String dedupeKey;
}
