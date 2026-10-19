package com.tiktok.userservice.entity;

/**
 * Who may reach the account through a given channel. Shared by {@code commentAudience} and
 * {@code dmAudience} because the three answers are the same question asked twice, and a client
 * that renders one picker can render the other.
 *
 * <p>{@code FRIENDS} means mutual followers — the same relationship
 * {@link com.tiktok.userservice.service.FollowService#friendship} answers, not "people I follow".
 */
public enum SettingsAudience {
    EVERYONE,
    FRIENDS,
    NO_ONE
}
