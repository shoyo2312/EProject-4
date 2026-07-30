package com.tiktok.recommendationservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InboxServiceImplTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private InboxServiceImpl inboxService;

    @BeforeEach
    void setUp() {
        inboxService = new InboxServiceImpl(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void markIfNew_firstTime_returnsTrue() {
        when(valueOperations.setIfAbsent(eq("reco:inbox:evt-1"), eq("1"), eq(Duration.ofDays(7))))
                .thenReturn(true);

        assertThat(inboxService.markIfNew("evt-1")).isTrue();
    }

    @Test
    void markIfNew_alreadySeen_returnsFalse() {
        when(valueOperations.setIfAbsent(eq("reco:inbox:evt-1"), eq("1"), eq(Duration.ofDays(7))))
                .thenReturn(false);

        assertThat(inboxService.markIfNew("evt-1")).isFalse();
    }
}
