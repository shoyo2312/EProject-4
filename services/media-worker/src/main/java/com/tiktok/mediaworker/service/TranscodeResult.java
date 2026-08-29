package com.tiktok.mediaworker.service;

public record TranscodeResult(
        String thumbnailUrl,
        String hlsUrl,
        /** Null until something decodes the file — see TranscodeServiceImpl. Never 0 as a stand-in. */
        Integer durationSeconds
) {
}
