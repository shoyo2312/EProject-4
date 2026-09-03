package com.tiktok.mediaworker.service;

/**
 * One question media-worker needs answered about an upload before it will transcode it:
 * how long is it. Split behind an interface so TranscodeServiceImpl's size/duration rules
 * are unit-testable without the ffprobe binary or a presigned URL.
 */
public interface VideoProbe {

    /**
     * @param httpUrl an {@code http(s)://} or {@code file://} URL ffprobe can open — for the
     *                real upload this is a short-lived presigned GET
     * @return the media duration rounded to the nearest second
     * @throws IllegalStateException if the URL yields no readable duration
     */
    int durationSeconds(String httpUrl);
}
