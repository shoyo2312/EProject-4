package com.tiktok.userservice.repository;

import com.tiktok.userservice.entity.ScreenTimeDay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface ScreenTimeDayRepository extends JpaRepository<ScreenTimeDay, Long> {

    /**
     * Adds to the day's totals, creating the row on the day's first heartbeat. One statement, so
     * concurrent heartbeats queue on the row instead of reading the same total and each writing
     * total + 60. {@code id} is only used when this inserts.
     */
    @Modifying
    @Query(value = """
            INSERT INTO screen_time_days (id, user_id, local_date, day_seconds, night_seconds, created_at, updated_at)
            VALUES (:id, :userId, :localDate, :daySeconds, :nightSeconds, now(), now())
            ON CONFLICT (user_id, local_date) WHERE deleted_at IS NULL DO UPDATE
            SET day_seconds = screen_time_days.day_seconds + EXCLUDED.day_seconds,
                night_seconds = screen_time_days.night_seconds + EXCLUDED.night_seconds,
                updated_at = now()
            """, nativeQuery = true)
    int addSeconds(@Param("id") Long id,
                   @Param("userId") Long userId,
                   @Param("localDate") LocalDate localDate,
                   @Param("daySeconds") int daySeconds,
                   @Param("nightSeconds") int nightSeconds);

    List<ScreenTimeDay> findByUserIdAndLocalDateBetweenAndDeletedAtIsNull(Long userId, LocalDate from, LocalDate to);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ScreenTimeDay d set d.deletedAt = :at where d.userId = :userId and d.deletedAt is null")
    int softDeleteAllOf(@Param("userId") Long userId, @Param("at") Instant at);
}
