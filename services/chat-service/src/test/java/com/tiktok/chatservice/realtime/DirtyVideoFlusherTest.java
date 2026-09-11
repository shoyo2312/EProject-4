package com.tiktok.chatservice.realtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DirtyVideoFlusherTest {

    private DirtyVideoRegistry registry;
    private SubscriptionTracker tracker;
    private InteractionCountsClient countsClient;
    private SimpMessagingTemplate messaging;
    private DirtyVideoFlusher flusher;

    @BeforeEach
    void setUp() {
        registry = new DirtyVideoRegistry();
        tracker = new SubscriptionTracker();
        countsClient = mock(InteractionCountsClient.class);
        messaging = mock(SimpMessagingTemplate.class);
        flusher = new DirtyVideoFlusher(registry, tracker, countsClient, messaging);
    }

    @Test
    void manyEventsOnOneVideoProduceOneFrame() {
        tracker.subscribed("s", "1", "/topic/videos.100");
        registry.markDirty("100");
        registry.markDirty("100");
        registry.markDirty("100");
        when(countsClient.fetch(List.of("100")))
                .thenReturn(List.of(VideoFrame.counts("100", 3, 0, 0, 0, 0)));

        flusher.flush();

        verify(messaging, times(1)).convertAndSend(eq("/topic/videos.100"), any(VideoFrame.class));
    }

    @Test
    void aVideoNobodyIsWatchingIsNeverFetched() {
        registry.markDirty("200");

        flusher.flush();

        verifyNoInteractions(countsClient);
        verify(messaging, never()).convertAndSend(any(String.class), any(VideoFrame.class));
    }

    @Test
    void moreThanFiftyIdsAreSplitAcrossCalls() {
        IntStream.rangeClosed(1, 60).forEach(i -> {
            tracker.subscribed("s", String.valueOf(i), "/topic/videos." + i);
            registry.markDirty(String.valueOf(i));
        });
        when(countsClient.fetch(anyList())).thenReturn(List.of());

        flusher.flush();

        verify(countsClient, times(2)).fetch(anyList());
    }

    @Test
    void theSetIsClearedSoAQuietWindowSendsNothing() {
        tracker.subscribed("s", "1", "/topic/videos.100");
        registry.markDirty("100");
        when(countsClient.fetch(List.of("100")))
                .thenReturn(List.of(VideoFrame.counts("100", 1, 0, 0, 0, 0)));

        flusher.flush();
        flusher.flush();

        verify(countsClient, times(1)).fetch(anyList());
    }

    @Test
    void aFailingFetchDoesNotEscape() {
        tracker.subscribed("s", "1", "/topic/videos.100");
        registry.markDirty("100");
        when(countsClient.fetch(anyList())).thenThrow(new RuntimeException("interaction-service is down"));

        flusher.flush();

        verify(messaging, never()).convertAndSend(any(String.class), any(VideoFrame.class));
    }
}
