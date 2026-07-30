package com.tiktok.recommendationservice.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class InboxServiceImpl implements InboxService {

    private static final String KEY_PREFIX = "reco:inbox:";
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean markIfNew(String eventId) {
        Boolean firstSeen = redisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + eventId, "1", TTL);
        return Boolean.TRUE.equals(firstSeen);
    }
}
