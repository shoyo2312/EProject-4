package com.tiktok.interactionservice.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

/**
 * One user's favourites in the order they saved them. {@link SaveByUser} answers "is this video
 * saved"; this one answers "list my saves", and only exists because a single table cannot be
 * clustered on both video_id and created_at.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("saves_by_user_time")
public class SaveByUserTime {

    @PrimaryKey
    private SaveByUserTimeKey key;
}
