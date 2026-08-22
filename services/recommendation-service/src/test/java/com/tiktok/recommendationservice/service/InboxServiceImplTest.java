package com.tiktok.recommendationservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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

    @Test
    void runOnce_firstDelivery_runsTheWork() {
        givenClaimGranted();
        AtomicInteger runs = new AtomicInteger();

        inboxService.runOnce("evt-1", runs::incrementAndGet);

        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void runOnce_redelivery_doesNotRunTheWork() {
        when(valueOperations.setIfAbsent(eq("reco:inbox:evt-1"), eq("1"), eq(Duration.ofDays(7))))
                .thenReturn(false);
        AtomicInteger runs = new AtomicInteger();

        inboxService.runOnce("evt-1", runs::incrementAndGet);

        assertThat(runs.get()).isZero();
    }

    /**
     * The failure this exists for. Without the release, kafka-lib's retry hands the event back,
     * the claim from the failed attempt is still standing, the work is skipped as a duplicate,
     * and the offset commits — an event lost with nothing in the DLT to show for it.
     */
    @Test
    void runOnce_whenTheWorkFails_releasesTheClaimAndRethrows() {
        givenClaimGranted();

        assertThatThrownBy(() -> inboxService.runOnce("evt-1", () -> {
            throw new IllegalStateException("redis blipped");
        })).isInstanceOf(IllegalStateException.class);

        verify(redisTemplate).delete("reco:inbox:evt-1");
    }

    private void givenClaimGranted() {
        when(valueOperations.setIfAbsent(eq("reco:inbox:evt-1"), eq("1"), eq(Duration.ofDays(7))))
                .thenReturn(true);
    }
}
