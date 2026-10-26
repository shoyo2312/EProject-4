package com.tiktok.userservice.entity;

import com.tiktok.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;

/**
 * Seconds on the app for one account on one local date. Read through JPA, written only by
 * {@link com.tiktok.userservice.repository.ScreenTimeDayRepository#addSeconds}. See V19.
 */
@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "screen_time_days")
public class ScreenTimeDay extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** In the zone the heartbeats came from. */
    @Column(name = "local_date", nullable = false)
    private LocalDate localDate;

    /** 06:00–22:00 local. */
    @Column(name = "day_seconds", nullable = false)
    private int daySeconds;

    /** 22:00–06:00 local. */
    @Column(name = "night_seconds", nullable = false)
    private int nightSeconds;
}
