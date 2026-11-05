package com.tiktok.analyticsservice.dto.response;

/** Views of one video that came through one surface: a {@code WatchSource} name, {@code OTHER} for the unknown. */
public record TrafficSourceResponse(String source, long views) {
}
