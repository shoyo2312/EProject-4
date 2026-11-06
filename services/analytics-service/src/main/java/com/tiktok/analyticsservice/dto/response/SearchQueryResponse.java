package com.tiktok.analyticsservice.dto.response;

/** Views of one video that came from searching {@code query}, lower-cased as the viewer typed it. */
public record SearchQueryResponse(String query, long views) {
}
