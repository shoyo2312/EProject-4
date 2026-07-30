package com.tiktok.analyticsservice.repository;

import com.tiktok.analyticsservice.dto.response.DailySignupResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class UserSignupEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public void insert(String eventId, Long userId, String username, Instant occurredAt) {
        jdbcTemplate.update(
                "INSERT INTO user_signup_events (event_id, user_id, username, occurred_at) VALUES (?, ?, ?, ?)",
                eventId, userId, username, Timestamp.from(occurredAt));
    }

    public List<DailySignupResponse> findDailySignups(int days) {
        return jdbcTemplate.query(
                """
                SELECT toDate(occurred_at) AS day, count() AS cnt
                FROM user_signup_events FINAL
                WHERE occurred_at >= now() - INTERVAL ? DAY
                GROUP BY day
                ORDER BY day
                """,
                (rs, rowNum) -> new DailySignupResponse(rs.getDate("day").toLocalDate(), rs.getLong("cnt")),
                days);
    }
}
