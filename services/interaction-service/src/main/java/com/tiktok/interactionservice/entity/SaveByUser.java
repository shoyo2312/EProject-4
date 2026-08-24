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
 * A video a user saved to their favourites. Unlike a like there is no per-video mirror table:
 * nothing asks "who saved video X", so this one partition answers both the status check and the
 * listing.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("saves_by_user")
public class SaveByUser {

    @PrimaryKey
    private SaveByUserKey key;

    @Column("created_at")
    @CassandraType(type = CassandraType.Name.TIMESTAMP)
    private Instant createdAt;
}
