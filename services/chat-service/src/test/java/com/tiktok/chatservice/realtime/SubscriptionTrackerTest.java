package com.tiktok.chatservice.realtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionTrackerTest {

    private final SubscriptionTracker tracker = new SubscriptionTracker();

    @Test
    void aVideoWithNoSubscribersIsNotWatched() {
        assertThat(tracker.isWatched("100")).isFalse();
    }

    @Test
    void subscribingMakesItWatched() {
        tracker.subscribed("session-1", "sub-1", "/topic/videos.100");

        assertThat(tracker.isWatched("100")).isTrue();
    }

    @Test
    void theLastUnsubscribeStopsIt() {
        tracker.subscribed("session-1", "sub-1", "/topic/videos.100");
        tracker.subscribed("session-2", "sub-1", "/topic/videos.100");
        tracker.unsubscribed("session-1", "sub-1");

        assertThat(tracker.isWatched("100")).isTrue();

        tracker.unsubscribed("session-2", "sub-1");

        assertThat(tracker.isWatched("100")).isFalse();
    }

    @Test
    void aDisconnectDropsEverySubscriptionOfThatSession() {
        tracker.subscribed("session-1", "sub-1", "/topic/videos.100");
        tracker.subscribed("session-1", "sub-2", "/topic/videos.101");
        tracker.disconnected("session-1");

        assertThat(tracker.isWatched("100")).isFalse();
        assertThat(tracker.isWatched("101")).isFalse();
    }

    @Test
    void theCommentChannelCountsAsWatchingTheVideo() {
        tracker.subscribed("session-1", "sub-1", "/topic/videos.100.comments");

        assertThat(tracker.isWatched("100")).isTrue();
    }

    @Test
    void destinationsThatAreNotVideoTopicsAreIgnored() {
        tracker.subscribed("session-1", "sub-1", "/topic/conversations.7");

        assertThat(tracker.isWatched("7")).isFalse();
    }
}
