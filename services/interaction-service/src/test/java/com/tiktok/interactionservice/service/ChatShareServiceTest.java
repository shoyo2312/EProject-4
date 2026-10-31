package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.entity.*;
import com.tiktok.interactionservice.event.producer.InteractionEventPublisher;
import com.tiktok.interactionservice.repository.*;
import com.tiktok.interactionservice.client.VideoOwnershipClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatShareServiceTest {
    private final ChatShareDedupeRepository dedupe = mock(ChatShareDedupeRepository.class);
    private final ShareByVideoRepository rows = mock(ShareByVideoRepository.class);
    private final VideoCountersRepository counters = mock(VideoCountersRepository.class);
    private final CounterCacheService cache = mock(CounterCacheService.class);
    private final InteractionEventPublisher events = mock(InteractionEventPublisher.class);
    private final InteractionRateLimiter limiter = mock(InteractionRateLimiter.class);
    private final VideoOwnershipClient visibility = mock(VideoOwnershipClient.class);
    private final ShareServiceImpl persistence = new ShareServiceImpl(rows, counters, cache, events, limiter, visibility);
    private final ChatShareService service = new ChatShareService(dedupe, persistence);

    @Test
    void appliedClaimCountsOnceWithoutPublicVisibilityOrRateLimitChecks() {
        when(dedupe.claim(eq(123L), eq("message"), anyLong())).thenReturn(true, false);
        assertThat(service.count(123L, 1L, "message")).isTrue();
        assertThat(service.count(123L, 1L, "message")).isFalse();
        ArgumentCaptor<ShareByVideo> saved = ArgumentCaptor.forClass(ShareByVideo.class);
        verify(rows).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(1L);
        verify(counters).incrementShareCount(123L, 1);
        verify(cache).invalidate(123L);
        verify(events).publishShare(saved.getValue().getKey().getShareId(), 123L, 1L);
        verifyNoInteractions(limiter, visibility);
    }

    @Test
    void duplicateDoesNothing() {
        assertThat(service.count(123L, 1L, "message")).isFalse();
        verifyNoInteractions(rows, counters, cache, events, limiter, visibility);
    }

    @Test
    void failedPublishUsesPublicCompensationAndReleasesClaimForRetry() {
        when(dedupe.claim(anyLong(), anyString(), anyLong())).thenReturn(true);
        doThrow(new IllegalStateException("broker down")).when(events).publishShare(anyLong(), anyLong(), anyLong());
        assertThatThrownBy(() -> service.count(123L, 1L, "message")).hasMessage("broker down");
        verify(counters).incrementShareCount(123L, 1);
        verify(counters).incrementShareCount(123L, -1);
        verify(rows).deleteById(any());
        ArgumentCaptor<ChatShareDedupeKey> released = ArgumentCaptor.forClass(ChatShareDedupeKey.class);
        verify(dedupe).deleteById(released.capture());
        assertThat(released.getValue().getVideoId()).isEqualTo(123L);
        assertThat(released.getValue().getDedupeKey()).isEqualTo("message");
    }

    @Test
    void failedCounterDeletesShareAndClaimWithoutDecrementingAgain() {
        when(dedupe.claim(anyLong(), anyString(), anyLong())).thenReturn(true);
        doThrow(new IllegalStateException("cassandra down")).when(counters).incrementShareCount(123L, 1);
        assertThatThrownBy(() -> service.count(123L, 1L, "message")).hasMessage("cassandra down");
        verify(counters, never()).incrementShareCount(123L, -1);
        verify(rows).deleteById(any());
        verify(dedupe).deleteById(any());
        verifyNoInteractions(cache, events);
    }

    @Test
    void failedRowWriteReleasesClaim() {
        when(dedupe.claim(anyLong(), anyString(), anyLong())).thenReturn(true);
        when(rows.save(any())).thenThrow(new IllegalStateException("write failed"));
        assertThatThrownBy(() -> service.count(123L, 1L, "message")).hasMessage("write failed");
        verify(dedupe).deleteById(any());
        verifyNoInteractions(counters, cache, events);
    }
}
