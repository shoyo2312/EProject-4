package com.tiktok.mediaworker.service;

public record TranscodeResult(
        String thumbnailUrl,
        String hlsUrl,
        int durationSeconds
) {
}
