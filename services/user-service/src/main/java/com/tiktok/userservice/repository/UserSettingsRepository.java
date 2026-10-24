package com.tiktok.userservice.repository;

import com.tiktok.userservice.entity.UserSettings;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserSettingsRepository extends JpaRepository<UserSettings, Long> {

    Optional<UserSettings> findByUserIdAndDeletedAtIsNull(Long userId);

    /**
     * The rows that exist among {@code userIds} — ids with no row are simply absent, and the
     * caller fills them in with {@link UserSettings#defaultsFor}. One query, so the internal batch
     * endpoint costs the same whether it is asked for one id or a hundred.
     */
    List<UserSettings> findByUserIdInAndDeletedAtIsNull(Collection<Long> userIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update UserSettings s set s.deletedAt = :at where s.userId = :userId and s.deletedAt is null")
    int softDeleteByUserId(@Param("userId") Long userId, @Param("at") Instant at);

    /**
     * Stores the account's zone, inserting the settings row if there is none yet — every other
     * column then takes the table's DEFAULT, which mirrors {@link UserSettings#defaultsFor}.
     *
     * <p>The only writer of {@code time_zone}: the entity maps it read-only, so a PATCH that read
     * the row before this ran cannot put the old zone back. It also leaves {@code version} alone,
     * so that PATCH is not turned into a 409 either. {@code newRowId} is only used when this inserts.
     */
    @Modifying
    @Query(value = """
            INSERT INTO user_settings (id, user_id, time_zone, created_at, updated_at)
            VALUES (:newRowId, :userId, :timeZone, now(), now())
            ON CONFLICT (user_id) WHERE deleted_at IS NULL DO UPDATE
            SET time_zone = EXCLUDED.time_zone, updated_at = now()
            WHERE user_settings.time_zone IS DISTINCT FROM EXCLUDED.time_zone
            """, nativeQuery = true)
    int upsertTimeZone(@Param("newRowId") Long newRowId,
                       @Param("userId") Long userId,
                       @Param("timeZone") String timeZone);

    /**
     * Accounts the weekly screen time job may owe an update: the switch on, a known zone, and no
     * update yet for {@code latestWeek} — the latest week any zone can be due. Keyset-paged by id.
     */
    @Query("select s from UserSettings s where s.weeklyScreenTimeDigest = true and s.timeZone is not null "
            + "and s.deletedAt is null and (s.lastDigestWeek is null or s.lastDigestWeek < :latestWeek) "
            + "and s.id > :afterId order by s.id")
    List<UserSettings> findDigestCandidates(@Param("latestWeek") LocalDate latestWeek,
                                            @Param("afterId") Long afterId,
                                            Pageable page);

    /**
     * Claims {@code week} for the account's update. 1 means this caller won and must send; 0 means
     * the week (or a later one) is already claimed. Leaves {@code version} alone, so a settings save
     * running alongside is not turned into a 409.
     */
    @Modifying
    @Query(value = "UPDATE user_settings SET last_digest_week = :week "
            + "WHERE id = :id AND (last_digest_week IS NULL OR last_digest_week < :week)", nativeQuery = true)
    int claimDigestWeek(@Param("id") Long settingsId, @Param("week") LocalDate week);
}
