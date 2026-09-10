package com.tiktok.chatservice.realtime;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The videos whose counters have moved since the last flush. A set, so a hundred likes in one
 * window are one entry — this is where a like/unlike burst stops being a burst.
 */
@Component
public class DirtyVideoRegistry {

    private final Set<String> dirty = ConcurrentHashMap.newKeySet();

    public void markDirty(String videoId) {
        if (videoId != null && !videoId.isBlank()) {
            dirty.add(videoId);
        }
    }

    /**
     * Takes the current contents and removes exactly those. Events arriving during the flush land
     * in the set and go out in the next window rather than being dropped with the drained ones.
     */
    public Set<String> drain() {
        Set<String> drained = Set.copyOf(dirty);
        dirty.removeAll(drained);
        return drained;
    }
}
