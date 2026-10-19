package com.tiktok.adminservice.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The moderation term list's revision — the one row in {@code moderation_terms_revision}. The
 * services compare it with the revision they hold and rebuild their matcher when it moved.
 *
 * <p>Plain JDBC: it is a counter, not an entity, and {@code UPDATE ... RETURNING} is one statement
 * that both moves it and says where to. It runs on the JPA transaction's connection.
 */
@Repository
@RequiredArgsConstructor
public class ModerationTermRevisions {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Moves the revision on, holding the row until the caller's transaction ends — call it first in
     * every change, so changes queue here and commit in revision order (see V7).
     */
    public long next() {
        return jdbcTemplate.queryForObject(
                "UPDATE moderation_terms_revision SET revision = revision + 1 WHERE id = 1 RETURNING revision",
                Long.class);
    }

    public long current() {
        return jdbcTemplate.queryForObject("SELECT revision FROM moderation_terms_revision WHERE id = 1", Long.class);
    }
}
