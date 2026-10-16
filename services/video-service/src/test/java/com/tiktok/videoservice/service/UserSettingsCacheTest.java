package com.tiktok.videoservice.service;

import com.tiktok.videoservice.client.OwnerCommentSettings;
import com.tiktok.videoservice.config.UserSettingsCacheProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JSON round trip against a real Redis. A value that stopped parsing would not fail anything —
 * every read would quietly be a miss, and every {@code /policy} read a call to user-service.
 */
@Testcontainers
class UserSettingsCacheTest {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private final UserSettingsCache cache = new UserSettingsCache(redisTemplate,
            new UserSettingsCacheProperties(true, Duration.ofSeconds(30)), JsonMapper.builder().findAndAddModules().build());

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void flush() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void whatIsPut_isReadBack_oneAtATimeOrAPageAtOnce() {
        OwnerCommentSettings seven = new OwnerCommentSettings("FRIENDS", false, true, List.of("spoiler", "tạch"));
        OwnerCommentSettings eight = new OwnerCommentSettings("EVERYONE", null, null, null);

        cache.putAll(Map.of(7L, seven, 8L, eight));

        assertThat(cache.get(7L)).contains(seven);
        assertThat(cache.getAll(List.of(7L, 8L, 9L))).isEqualTo(Map.of(7L, seven, 8L, eight));
        assertThat(redisTemplate.getExpire("user-settings:comment-policy:7")).isPositive();
    }

    /** An entry written by something else, or a bare audience under the old shape, is a miss — not an error. */
    @Test
    void aValueThatDoesNotParse_isAMiss() {
        redisTemplate.opsForValue().set("user-settings:comment-policy:7", "FRIENDS");

        assertThat(cache.get(7L)).isEmpty();
        assertThat(cache.getAll(List.of(7L))).isEmpty();
    }

    @Test
    void switchedOff_itNeitherReadsNorWrites() {
        UserSettingsCache off = new UserSettingsCache(redisTemplate,
                new UserSettingsCacheProperties(false, Duration.ofSeconds(30)), JsonMapper.builder().build());

        off.putAll(Map.of(7L, new OwnerCommentSettings("FRIENDS", true, false, List.of())));

        assertThat(redisTemplate.keys("user-settings:*")).isEmpty();
        assertThat(off.get(7L)).isEmpty();
    }
}
