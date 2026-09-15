package com.tiktok.chatservice.realtime;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** The users whose follower/following/like counts moved since the last flush. See DirtyVideoRegistry. */
@Component
public class DirtyUserRegistry {

    private final Set<String> dirty = ConcurrentHashMap.newKeySet();

    public void markDirty(String userId) {
        if (userId != null && !userId.isBlank()) {
            dirty.add(userId);
        }
    }

    public Set<String> drain() {
        Set<String> drained = Set.copyOf(dirty);
        dirty.removeAll(drained);
        return drained;
    }
}
