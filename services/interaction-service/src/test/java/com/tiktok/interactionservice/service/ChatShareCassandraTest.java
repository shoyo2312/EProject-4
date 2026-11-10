package com.tiktok.interactionservice.service;

import com.datastax.oss.driver.api.core.CqlSession;
import com.tiktok.interactionservice.AbstractInteractionServiceIT;
import com.tiktok.interactionservice.repository.ChatShareDedupeRepository;
import com.tiktok.interactionservice.repository.ShareByVideoRepository;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.*;

/** Verifies the actual LWT binding, TTL, and counter effect against Cassandra. */
class ChatShareCassandraTest extends AbstractInteractionServiceIT {
    @Autowired private ChatShareService service;
    @Autowired private ChatShareDedupeRepository dedupe;
    @Autowired private ShareByVideoRepository rows;
    @Autowired private VideoCountersRepository counters;
    @Autowired private StringRedisTemplate redis;
    @Autowired private CqlSession session;

    @BeforeEach
    void cleanup() {
        dedupe.deleteAll();
        rows.deleteAll();
        counters.deleteAll();
        redis.getConnectionFactory().getConnection().flushAll();
    }

    @Test
    void deliveredMessageIsCountedOnceAndClaimExpiresIn30Days() {
        assertThat(service.count(123L, 1L, "message")).isTrue();
        assertThat(service.count(123L, 1L, "message")).isFalse();
        assertThat(rows.count()).isEqualTo(1);
        assertThat(counters.findById(123L).orElseThrow().getShareCount()).isEqualTo(1);
        var row = session.execute("SELECT TTL(share_id) AS remaining FROM chat_share_dedupe WHERE video_id = 123 AND dedupe_key = 'message'").one();
        assertThat(row.getInt("remaining")).isBetween(2591900, 2592000);
        assertThat(service.count(123L, 1L, "second-message")).isTrue();
        assertThat(counters.findById(123L).orElseThrow().getShareCount()).isEqualTo(2);
    }
}
