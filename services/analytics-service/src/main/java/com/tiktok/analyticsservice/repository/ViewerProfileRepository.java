package com.tiktok.analyticsservice.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The one dimension table: a viewer's birth date, for age groups. Written as a plain INSERT —
 * ReplacingMergeTree(updated_at) keeps the newest row per user_id and every read uses FINAL, so
 * an upsert is an insert here. Direct rather than through BatchWriter: a trickle, not a stream.
 */
@Repository
@RequiredArgsConstructor
public class ViewerProfileRepository {

    private final JdbcTemplate jdbcTemplate;

    public void upsert(long userId, LocalDate birthDate, Instant updatedAt) {
        jdbcTemplate.update(
                "INSERT INTO viewer_profiles (user_id, birth_date, updated_at) VALUES (?, ?, ?)",
                userId, Date.valueOf(birthDate), updatedAt);
    }
}
