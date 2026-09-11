package com.tiktok.chatservice.realtime;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Which videos this instance currently has a listener for. Without it the flusher would fetch
 * counters for every video that moved anywhere on the platform, which is most of them, and throw
 * nearly all of the answers away.
 *
 * <p>Per instance, deliberately. Every replica consumes every record (see the group-id in
 * application.yml), so each one asks only about the videos its own clients are watching.
 */
@Component
public class SubscriptionTracker {

    /** videoId to the number of live subscriptions on it, across every session on this instance. */
    private final Map<String, AtomicInteger> watchers = new ConcurrentHashMap<>();

    /** (sessionId, subscriptionId) to the videoId it watches, so an unsubscribe can undo it. */
    private final Map<String, String> bySubscription = new ConcurrentHashMap<>();

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        subscribed(accessor.getSessionId(), accessor.getSubscriptionId(), accessor.getDestination());
    }

    @EventListener
    public void onUnsubscribe(SessionUnsubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        unsubscribed(accessor.getSessionId(), accessor.getSubscriptionId());
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        disconnected(event.getSessionId());
    }

    void subscribed(String sessionId, String subscriptionId, String destination) {
        String videoId = VideoTopics.videoIdOf(destination);
        if (videoId == null || sessionId == null || subscriptionId == null) {
            return;
        }
        bySubscription.put(key(sessionId, subscriptionId), videoId);
        watchers.computeIfAbsent(videoId, id -> new AtomicInteger()).incrementAndGet();
    }

    void unsubscribed(String sessionId, String subscriptionId) {
        release(bySubscription.remove(key(sessionId, subscriptionId)));
    }

    void disconnected(String sessionId) {
        if (sessionId == null) {
            return;
        }
        String prefix = sessionId + " ";
        bySubscription.entrySet().removeIf(entry -> {
            if (entry.getKey().startsWith(prefix)) {
                release(entry.getValue());
                return true;
            }
            return false;
        });
    }

    public boolean isWatched(String videoId) {
        AtomicInteger count = watchers.get(videoId);
        return count != null && count.get() > 0;
    }

    /**
     * Removes the entry when it reaches zero rather than leaving a zeroed counter behind: this map
     * is keyed by videoId and would otherwise grow for the lifetime of the process, one entry per
     * video anyone ever scrolled past.
     */
    private void release(String videoId) {
        if (videoId == null) {
            return;
        }
        watchers.computeIfPresent(videoId, (id, count) -> count.decrementAndGet() <= 0 ? null : count);
    }

    private static String key(String sessionId, String subscriptionId) {
        return sessionId + " " + subscriptionId;
    }
}
