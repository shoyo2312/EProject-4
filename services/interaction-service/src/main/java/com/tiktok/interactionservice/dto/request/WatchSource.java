package com.tiktok.interactionservice.dto.request;

/**
 * Where the viewer was when the video played. The client knows which surface rendered the card;
 * the server cannot infer it. {@code OTHER} is never sent — it is what analytics stores for a
 * report without a source (older clients, the mobile app).
 */
public enum WatchSource {
    FOR_YOU, FOLLOWING, FRIENDS, EXPLORE, PROFILE, SEARCH, MESSAGE, DIRECT, OTHER
}
