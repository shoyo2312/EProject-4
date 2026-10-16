package com.tiktok.recommendationservice.service;

import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link RedisPipeline} against a mocked template: runs the callback on the mocks themselves and
 * answers with what each call returned, in call order — the list a real pipeline hands back. Left
 * to itself a mock never runs the callback, and every stub and verify on a call made inside a
 * pipeline would quietly stop applying.
 */
final class MockPipeline {

    private MockPipeline() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void install(StringRedisTemplate template) {
        when(template.executePipelined(any(SessionCallback.class))).thenAnswer(invocation -> {
            List<Object> results = new ArrayList<>();
            SessionCallback callback = invocation.getArgument(0);
            callback.execute(recording(RedisOperations.class, template, results));
            return results;
        });
    }

    /** Forwards each call to {@code target} and records its answer; opsForX() hands out recorders too. */
    @SuppressWarnings("unchecked")
    private static <T> T recording(Class<T> type, Object target, List<Object> results) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            Object value;
            try {
                value = method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            if (method.getName().startsWith("opsFor")) {
                return recording(method.getReturnType(), value, results);
            }
            results.add(value);
            return null;
        });
    }
}
