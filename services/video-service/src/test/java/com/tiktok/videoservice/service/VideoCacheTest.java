package com.tiktok.videoservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.videoservice.config.VideoCacheProperties;
import com.tiktok.videoservice.dto.response.VideoResponse;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VideoCacheTest {

    private static final VideoCacheProperties ENABLED =
            new VideoCacheProperties(true, Duration.ofSeconds(60));

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    /**
     * Built the way Boot builds its own bean, not with {@code new ObjectMapper()}: the bare one
     * cannot write an Instant, so these tests would fail on a serializer the application never
     * uses. The round trip below pins that equivalence.
     */
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    /**
     * The one failure with no symptom on the read path: if a VideoResponse cannot survive a
     * round trip through the ObjectMapper the application actually builds, every entry written
     * is unreadable, {@code getAll} logs at debug and reports a miss, and the only evidence is a
     * cache that never hits. Instant and the two enums are what would break it, so they are what
     * this asserts on.
     *
     * <p>Run against Boot's autoconfigured mapper rather than a bare {@code new ObjectMapper()},
     * because the bare one cannot write an Instant at all and would pass a test the real
     * configuration fails, or the reverse.
     */
    @Test
    void videoResponseSurvivesARoundTripThroughBootsObjectMapper() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> {
                    ObjectMapper mapper = context.getBean(ObjectMapper.class);
                    VideoResponse original = video("A");

                    VideoResponse restored =
                            mapper.readValue(mapper.writeValueAsString(original), VideoResponse.class);

                    assertThat(restored).isEqualTo(original);
                });
    }

    @Test
    void get_returnsTheStoredVideo() throws Exception {
        VideoResponse stored = video("A");
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("video:A")).thenReturn(objectMapper.writeValueAsString(stored));

        assertThat(cache().get("A")).contains(stored);
    }

    @Test
    void get_whenNothingIsStored_isEmpty() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("video:A")).thenReturn(null);

        assertThat(cache().get("A")).isEmpty();
    }

    /**
     * Redis being down costs a Mongo query, not a 500. Every method here has to swallow, so
     * every method here is asserted — a single un-caught call site is enough to take the read
     * path down with the cache.
     */
    @Test
    void everyOperationFailsOpenWhenRedisIsDown() {
        RedisConnectionFailureException down = new RedisConnectionFailureException("no redis");
        when(redisTemplate.opsForValue()).thenThrow(down);
        when(redisTemplate.delete(anyString())).thenThrow(down);
        when(redisTemplate.executePipelined(org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.RedisCallback<Object>>any()))
                .thenThrow(down);

        VideoCache cache = cache();

        assertThat(cache.get("A")).isEmpty();
        assertThat(cache.getAll(List.of("A"))).isEmpty();
        assertThatCode(() -> cache.put(video("A"))).doesNotThrowAnyException();
        assertThatCode(() -> cache.evict("A")).doesNotThrowAnyException();
    }

    /**
     * A batch is one MGET, and a partially warm one comes back with nulls in the gaps. Mapping
     * those back onto the right ids is positional, so an off-by-one here would hand a caller
     * another video's metadata — which is why this asserts the pairing and not just the size.
     */
    @Test
    void getAll_mapsMgetResultsBackOntoTheirIds() throws Exception {
        VideoResponse b = video("B");
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.multiGet(List.of("video:A", "video:B", "video:C")))
                .thenReturn(Arrays.asList(null, objectMapper.writeValueAsString(b), null));

        assertThat(cache().getAll(List.of("A", "B", "C"))).containsExactly(entry("B", b));
    }

    /** A stale serialization format costs one id its hit, not the whole batch. */
    @Test
    void getAll_treatsAnUnreadableEntryAsAMiss() throws Exception {
        VideoResponse b = video("B");
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.multiGet(anyCollection()))
                .thenReturn(Arrays.asList("{not json", objectMapper.writeValueAsString(b)));

        assertThat(cache().getAll(List.of("A", "B"))).containsOnlyKeys("B");
    }

    /**
     * Disabled means no Redis traffic at all, not a cache that quietly still writes: this is the
     * switch someone flips at three in the morning when a stale entry is the suspect.
     */
    @Test
    void whenDisabled_noRedisCommandIsIssued() {
        VideoCache cache = new VideoCache(redisTemplate, objectMapper,
                new VideoCacheProperties(false, Duration.ofSeconds(60)));

        assertThat(cache.get("A")).isEmpty();
        assertThat(cache.getAll(List.of("A"))).isEmpty();
        cache.put(video("A"));
        cache.evict("A");

        org.mockito.Mockito.verifyNoInteractions(redisTemplate);
    }

    private VideoCache cache() {
        return new VideoCache(redisTemplate, objectMapper, ENABLED);
    }

    private static java.util.Map.Entry<String, VideoResponse> entry(String id, VideoResponse video) {
        return java.util.Map.entry(id, video);
    }

    private static VideoResponse video(String id) {
        return new VideoResponse(
                id, 42L, "title", "description", "https://cdn/t.jpg", "https://cdn/p.webp", "https://cdn/v.m3u8",
                10, VideoStatus.PUBLISHED, VideoVisibility.PUBLIC, 1, 2, 3L, false,
                List.of("tag"), Instant.parse("2026-08-27T10:00:00Z"), null);
    }
}
