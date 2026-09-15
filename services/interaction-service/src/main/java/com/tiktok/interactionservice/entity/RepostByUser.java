package com.tiktok.interactionservice.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

/**
 * Reverse index of {@link RepostByVideo}: "videos reposted by user X", newest repost first, kept
 * in sync with reposts_by_video on every repost/un-repost write. The repost's timestamp is in the
 * key — it is the listing's ordering — so there is nothing left outside it. Mirrors
 * {@link LikeByUser} exactly.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("reposts_by_user")
public class RepostByUser {

    @PrimaryKey
    private RepostByUserKey key;
}
