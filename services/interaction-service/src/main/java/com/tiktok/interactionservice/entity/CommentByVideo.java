package com.tiktok.interactionservice.entity;

import com.tiktok.common.id.SnowflakeIdGenerator;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.cassandra.core.mapping.CassandraType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("comments_by_video")
public class CommentByVideo {

    @PrimaryKey
    private CommentByVideoKey key;

    @Column("user_id")
    private Long userId;

    @Column("content")
    private String content;

    @Column("created_at")
    @CassandraType(type = CassandraType.Name.TIMESTAMP)
    private Instant createdAt;

    @Column("deleted_at")
    @CassandraType(type = CassandraType.Name.TIMESTAMP)
    private Instant deletedAt;

    public static Long newId() {
        return SnowflakeIdGenerator.nextId();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
