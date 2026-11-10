package com.tiktok.userservice.dto.request;

/** Which of an account's followers a story is for; the story's visibility decides. */
public enum StoryAudienceScope {

    /** Everyone following the account - a PUBLIC story. */
    FOLLOWERS,

    /** The followers the account follows back - a FRIENDS story. */
    FRIENDS
}
