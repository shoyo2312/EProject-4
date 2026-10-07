package com.tiktok.recommendationservice.service;

import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.function.Consumer;

/**
 * Several Redis calls in one round trip. The feed and every engagement event make a dozen or more
 * calls that do not wait on each other's answers, and sent one after another each paid a full
 * round trip. Inside {@code calls} every operation returns null; the answers come back from
 * {@link #run}, in the order the calls were made.
 *
 * <p>A pipeline holds a connection of its own while it runs, which is why this service depends on
 * commons-pool2: without a pool every pipeline would open and close a fresh TCP connection.
 */
final class RedisPipeline {

    private RedisPipeline() {
    }

    @SuppressWarnings("unchecked")
    static List<Object> run(StringRedisTemplate redisTemplate, Consumer<RedisOperations<String, String>> calls) {
        return redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public <K, V> Object execute(RedisOperations<K, V> operations) {
                calls.accept((RedisOperations<String, String>) operations);
                return null;
            }
        });
    }

    /** One answer out of {@link #run}'s list, typed as the call that produced it. */
    @SuppressWarnings("unchecked")
    static <T> T answer(List<Object> results, int index) {
        return results == null || index >= results.size() ? null : (T) results.get(index);
    }
}
