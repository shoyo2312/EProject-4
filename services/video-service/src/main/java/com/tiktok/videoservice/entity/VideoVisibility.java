package com.tiktok.videoservice.entity;

public enum VideoVisibility {
    /** Anyone, logged in or not. On the feed and on every profile grid. */
    PUBLIC,
    /**
     * Only the owner's mutual followers, plus the owner. Never on the feed — the feed query stays
     * PUBLIC-only — and only on the profile grid for a viewer video-service has confirmed is a
     * friend via user-service. See {@code VideoServiceImpl.isVisibleTo}.
     */
    FRIENDS,
    /** Only the owner. */
    PRIVATE
}
