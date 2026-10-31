package com.tiktok.userservice.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plan Postgres picks for the follow counter updates, against the real migrations. No Spring
 * context: only the schema is under test.
 *
 * <p>With enable_seqscan off the planner takes any usable index over a sequential scan, so an
 * empty table answers what a large one would: can this WHERE use an index at all? The partial
 * unique index on user_id cannot — it only serves queries that also say deleted_at IS NULL.
 */
@Testcontainers
class UserProfileUserIdIndexTest {

    @Container
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void followCounterUpdateFindsTheProfileThroughAnIndex() throws Exception {
        assertThat(plan("UPDATE user_profiles SET follower_count = follower_count + 1 WHERE user_id = 42"))
                .doesNotContain("Seq Scan");
    }

    private static String plan(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("SET enable_seqscan = off");
            StringBuilder plan = new StringBuilder();
            try (ResultSet rows = statement.executeQuery("EXPLAIN " + sql)) {
                while (rows.next()) {
                    plan.append(rows.getString(1)).append('\n');
                }
            }
            return plan.toString();
        }
    }
}
