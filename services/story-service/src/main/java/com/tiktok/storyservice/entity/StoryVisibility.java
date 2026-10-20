package com.tiktok.storyservice.entity;

/** The same three audiences, with the same meaning, as video-service's VideoVisibility. */
public enum StoryVisibility {
    /** Anyone — followers only, when the owner's account is private. */
    PUBLIC,
    /** The owner's mutual followers. */
    FRIENDS,
    /** Only the owner. */
    PRIVATE
}
