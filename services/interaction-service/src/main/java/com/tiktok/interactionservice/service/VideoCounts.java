package com.tiktok.interactionservice.service;

public record VideoCounts(
        long likeCount,
        long commentCount,
        long shareCount,
        long viewCount,
        long saveCount
) {
    public static final VideoCounts ZERO = new VideoCounts(0, 0, 0, 0, 0);
}
