package com.tiktok.userservice.entity;

/**
 * What a "Download your data" archive can hold. Only what this platform stores per user and can
 * list by user: comments are kept per video, with no index by author, so they cannot be offered.
 */
public enum DataExportCategory {
    DIRECT_MESSAGES,
    LIKES_AND_FAVORITES,
    POSTS,
    PROFILE_AND_SETTINGS,
    YOUR_ACTIVITY
}
