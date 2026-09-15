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
 * Which profiles this instance currently has a listener for — see {@code SubscriptionTracker},
 * whose video-topic version this mirrors. A separate component rather than a shared one because
 * the two track different topic namespaces and a video id and a user id are both bare Snowflakes;
 * merging them into one map would risk one colliding with the other.
 */
@Component
public class UserSubscriptionTracker {

    private final Map<String, AtomicInteger> watchers = new ConcurrentHashMap<>();
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
        String userId = UserTopics.userIdOf(destination);
        if (userId == null || sessionId == null || subscriptionId == null) {
            return;
        }
        bySubscription.put(key(sessionId, subscriptionId), userId);
        watchers.computeIfAbsent(userId, id -> new AtomicInteger()).incrementAndGet();
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

    public boolean isWatched(String userId) {
        AtomicInteger count = watchers.get(userId);
        return count != null && count.get() > 0;
    }

    private void release(String userId) {
        if (userId == null) {
            return;
        }
        watchers.computeIfPresent(userId, (id, count) -> count.decrementAndGet() <= 0 ? null : count);
    }

    private static String key(String sessionId, String subscriptionId) {
        return sessionId + " " + subscriptionId;
    }
}
