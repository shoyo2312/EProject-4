package com.tiktok.mediaworker.service;

public record TranscodeResult(
        String thumbnailUrl,
        /** Null when no animated preview could be produced — see TranscodeServiceImpl. */
        String previewUrl,
        String hlsUrl,
        /** Null until something decodes the file — see TranscodeServiceImpl. Never 0 as a stand-in. */
        Integer durationSeconds,
        /**
         * Display width and height — the size a player shows, with the container's rotation
         * already applied. Null when the file could not be measured; the client falls back to
         * its own default ratio then. See TranscodeServiceImpl#displaySize.
         */
        Integer width,
        Integer height
) {
}
