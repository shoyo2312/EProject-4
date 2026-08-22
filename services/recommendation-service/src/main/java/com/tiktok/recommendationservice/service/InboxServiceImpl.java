package com.tiktok.recommendationservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
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

    @Override
    public void runOnce(String eventId, Runnable work) {
        if (!markIfNew(eventId)) {
            log.debug("Skipping already-processed eventId={}", eventId);
            return;
        }

        try {
            work.run();
        } catch (RuntimeException ex) {
            log.error("Releasing claim on eventId={} after failure, will be redelivered", eventId, ex);
            // Best effort: if the release is itself what fails, the retry finds the claim
            // standing and skips the event — the same outcome as before this method existed.
            // Nothing better is available without a store that can hold the claim and the work
            // in one transaction, and Redis is the only store this service has.
            redisTemplate.delete(KEY_PREFIX + eventId);
            throw ex;
        }
    }
}
