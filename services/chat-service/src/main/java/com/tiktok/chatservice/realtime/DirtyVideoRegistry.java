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
     * Takes the current contents and removes exactly those. An event for a video that was NOT
     * drained lands in the set and goes out in the next window. One for a video that WAS drained,
     * arriving in the nanoseconds between the copy and the removal, is erased by that removal —
     * the copy and the remove are two steps, not one. The cost is a single stale render: the frame
     * already in flight carries a snapshot taken microseconds earlier, and the next event on that
     * video corrects it. Swap the backing set atomically instead if that ever needs to be airtight.
     */
    public Set<String> drain() {
        Set<String> drained = Set.copyOf(dirty);
        dirty.removeAll(drained);
        return drained;
    }
}
