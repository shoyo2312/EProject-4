package com.tiktok.authservice.entity;

public enum UserStatus {
    ACTIVE,
    LOCKED,
    /** Set by a moderation decision (admin-service BAN_USER). Every sign-in path already gates on ACTIVE. */
    BANNED
}
