package com.tiktok.interactionservice.client;

/**
 * The account-level answer to "who may comment on my videos", as video-service relays it from
 * user-service. A copy of user-service's enum rather than a shared type: there is no API-schema
 * library between these services, and the set is small and stable enough that a name this service
 * has never heard of is handled rather than prevented — see
 * {@code VideoOwnershipClient.audienceOf}.
 */
public enum CommentAudience {
    EVERYONE,
    FRIENDS,
    NO_ONE
}
