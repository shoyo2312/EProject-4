package com.tiktok.interactionservice.repository;

import com.tiktok.interactionservice.entity.UserBan;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface UserBanRepository extends CassandraRepository<UserBan, Long> {

    /**
     * Records a decision unless a newer one is already there. False when the row holds a later
     * decision — or does not exist yet, which {@link #insertIfAbsent} is for.
     */
    @Query("UPDATE user_bans SET banned_until = :bannedUntil, changed_at = :changedAt "
            + "WHERE user_id = :userId IF changed_at <= :changedAt")
    boolean updateIfNotNewer(@Param("userId") Long userId,
                             @Param("bannedUntil") Instant bannedUntil,
                             @Param("changedAt") Instant changedAt);

    /** The account's first decision. False when another one got there first. */
    @Query("INSERT INTO user_bans (user_id, banned_until, changed_at) "
            + "VALUES (:userId, :bannedUntil, :changedAt) IF NOT EXISTS")
    boolean insertIfAbsent(@Param("userId") Long userId,
                           @Param("bannedUntil") Instant bannedUntil,
                           @Param("changedAt") Instant changedAt);
}
