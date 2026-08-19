package com.tiktok.interactionservice.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.cassandra.core.mapping.CassandraType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;

/**
 * "User X already had a view counted for video Y". Unlike {@link LikeByVideo} the row is not a
 * permanent membership record — it is written with a TTL and disappears on its own, which is
 * what reopens the window for the same viewer to count again. Nothing reads it directly;
 * existence is checked as part of the LWT insert in {@code ViewByVideoRepository}.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("views_by_video")
public class ViewByVideo {

    @PrimaryKey
    private ViewByVideoKey key;

    @Column("viewed_at")
    @CassandraType(type = CassandraType.Name.TIMESTAMP)
    private Instant viewedAt;
}
