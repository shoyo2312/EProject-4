package com.tiktok.interactionservice.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

/**
 * Reverse index of LikeByVideo: "videos liked by user X", newest like first, kept in sync with
 * likes_by_video on every like/unlike write. The like's timestamp is in the key — it is the
 * listing's ordering — so there is nothing left outside it.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("likes_by_user")
public class LikeByUser {

    @PrimaryKey
    private LikeByUserKey key;
}
