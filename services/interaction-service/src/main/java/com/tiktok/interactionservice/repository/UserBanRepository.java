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

    /**
     * The first ban decision on a row the owner's closure created. {@code <=} is false against a
     * null, so {@link #updateIfNotNewer} never matches it, and the row's existence refuses
     * {@link #insertIfAbsent}.
     */
    @Query("UPDATE user_bans SET banned_until = :bannedUntil, changed_at = :changedAt "
            + "WHERE user_id = :userId IF changed_at = null")
    boolean updateIfUndecided(@Param("userId") Long userId,
                              @Param("bannedUntil") Instant bannedUntil,
                              @Param("changedAt") Instant changedAt);

    /** The account's first decision. False when another one got there first. */
    @Query("INSERT INTO user_bans (user_id, banned_until, changed_at) "
            + "VALUES (:userId, :bannedUntil, :changedAt) IF NOT EXISTS")
    boolean insertIfAbsent(@Param("userId") Long userId,
                           @Param("bannedUntil") Instant bannedUntil,
                           @Param("changedAt") Instant changedAt);

    /** {@link #updateIfNotNewer} for the owner's closure, on its own guard. */
    @Query("UPDATE user_bans SET deactivated_at = :deactivatedAt, account_state_changed_at = :changedAt "
            + "WHERE user_id = :userId IF account_state_changed_at <= :changedAt")
    boolean updateClosureIfNotNewer(@Param("userId") Long userId,
                                    @Param("deactivatedAt") Instant deactivatedAt,
                                    @Param("changedAt") Instant changedAt);

    /** {@link #updateIfUndecided} for the owner's closure, on a row a ban created. */
    @Query("UPDATE user_bans SET deactivated_at = :deactivatedAt, account_state_changed_at = :changedAt "
            + "WHERE user_id = :userId IF account_state_changed_at = null")
    boolean updateClosureIfUndecided(@Param("userId") Long userId,
                                     @Param("deactivatedAt") Instant deactivatedAt,
                                     @Param("changedAt") Instant changedAt);

    @Query("INSERT INTO user_bans (user_id, deactivated_at, account_state_changed_at) "
            + "VALUES (:userId, :deactivatedAt, :changedAt) IF NOT EXISTS")
    boolean insertClosureIfAbsent(@Param("userId") Long userId,
                                  @Param("deactivatedAt") Instant deactivatedAt,
                                  @Param("changedAt") Instant changedAt);
}
