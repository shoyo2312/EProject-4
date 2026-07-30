package com.tiktok.interactionservice.service;

public interface CounterCacheService {

    VideoCounts getCounts(Long videoId);

    void invalidate(Long videoId);
}
