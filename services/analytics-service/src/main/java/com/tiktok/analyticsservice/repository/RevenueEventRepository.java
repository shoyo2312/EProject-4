package com.tiktok.analyticsservice.repository;

import com.tiktok.analyticsservice.dto.response.DailyRevenueResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class RevenueEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public void insert(String eventId, String eventType, Long orderId, Long userId, BigDecimal amount, Instant occurredAt) {
        jdbcTemplate.update(
                "INSERT INTO revenue_events (event_id, event_type, order_id, user_id, amount, occurred_at) VALUES (?, ?, ?, ?, ?, ?)",
                eventId, eventType, orderId, userId, amount, Timestamp.from(occurredAt));
    }

    public List<DailyRevenueResponse> findDailyRevenue(int days) {
        return jdbcTemplate.query(
                """
                SELECT
                    toDate(occurred_at) AS day,
                    countIf(event_type = 'ORDER_CREATED') AS orders_created,
                    countIf(event_type = 'PAYMENT_COMPLETED') AS payments_completed,
                    sumIf(amount, event_type = 'PAYMENT_COMPLETED') AS revenue
                FROM revenue_events FINAL
                WHERE occurred_at >= now() - INTERVAL ? DAY
                GROUP BY day
                ORDER BY day
                """,
                (rs, rowNum) -> new DailyRevenueResponse(
                        rs.getDate("day").toLocalDate(),
                        rs.getLong("orders_created"),
                        rs.getLong("payments_completed"),
                        rs.getBigDecimal("revenue")),
                days);
    }
}
