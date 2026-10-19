package com.tiktok.interactionservice.service;

import java.util.List;
import java.util.Map;

public interface CounterCacheService {

    VideoCounts getCounts(Long videoId);

    /** The counts of every id, at a cost that does not grow with how many there are. */
    Map<Long, VideoCounts> getCounts(List<Long> videoIds);

    void invalidate(Long videoId);
}
