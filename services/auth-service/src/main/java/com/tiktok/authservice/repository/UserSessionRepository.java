package com.tiktok.authservice.repository;

import com.tiktok.authservice.entity.DeviceType;
import com.tiktok.authservice.entity.SessionEndReason;
import com.tiktok.authservice.entity.UserSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

    /**
     * The session row, locked until the transaction ends. Both a refresh and a remote sign-out
     * take this lock first, so one of them always sees the other's result: without it a sign-out
     * could revoke the session's tokens while a refresh was inserting the next one, and the new
     * token — invisible to the sign-out's UPDATE — would keep the device signed in.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM UserSession s WHERE s.id = :id")
    Optional<UserSession> findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT s FROM UserSession s WHERE s.userId = :userId AND s.revokedAt IS NULL " +
            "AND s.expiresAt > :now ORDER BY s.lastActiveAt DESC")
    List<UserSession> findActiveByUserId(@Param("userId") Long userId, @Param("now") Instant now);

    @Query("SELECT s.id FROM UserSession s WHERE s.userId = :userId AND s.deviceType = :type " +
            "AND s.revokedAt IS NULL AND s.expiresAt > :now")
    List<Long> findActiveIds(@Param("userId") Long userId, @Param("type") DeviceType type,
                             @Param("now") Instant now);

    @Query("SELECT s.id FROM UserSession s WHERE s.userId = :userId AND s.id <> :keep " +
            "AND s.revokedAt IS NULL AND s.expiresAt > :now")
    List<Long> findActiveIdsExcept(@Param("userId") Long userId, @Param("keep") Long keep,
                                   @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE UserSession s SET s.revokedAt = :now, s.revokeReason = :reason " +
            "WHERE s.id IN :ids AND s.revokedAt IS NULL")
    int revoke(@Param("ids") Collection<Long> ids, @Param("reason") SessionEndReason reason,
               @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE UserSession s SET s.revokedAt = :now, s.revokeReason = :reason " +
            "WHERE s.userId = :userId AND s.revokedAt IS NULL")
    int revokeAllByUserId(@Param("userId") Long userId, @Param("reason") SessionEndReason reason,
                          @Param("now") Instant now);

    /** A refresh: the device is still here, and its session now lasts as long as its new token. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE UserSession s SET s.lastActiveAt = :now, s.expiresAt = :expiresAt WHERE s.id = :id")
    int touch(@Param("id") Long id, @Param("now") Instant now, @Param("expiresAt") Instant expiresAt);

    /**
     * Deletes up to {@code batchSize} sessions that ended — expired or revoked — before
     * {@code cutoff}. Batched for the same reason as {@code RefreshTokenRepository.deleteExpiredBefore}.
     */
    @Modifying
    @Query(value = "DELETE FROM user_sessions WHERE id IN (" +
            "SELECT id FROM user_sessions WHERE expires_at < :cutoff OR revoked_at < :cutoff LIMIT :batchSize)",
            nativeQuery = true)
    int deleteEndedBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    @Modifying
    @Query("DELETE FROM UserSession s WHERE s.userId = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);
}
