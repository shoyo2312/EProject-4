package com.tiktok.mediaworker.service;

/**
 * What media-worker needs to know about an upload before it processes it: how long it is, and
 * whether it is already in the shape a browser plays. Split behind an interface so
 * TranscodeServiceImpl's rules are unit-testable without the ffmpeg binary.
 */
public interface VideoProbe {

    /**
     * @param url an {@code http(s)://} or {@code file://} URL ffmpeg can open
     * @return what could be read off the file, with the duration rounded to the nearest second
     * @throws IllegalStateException if the URL yields no readable video stream or duration
     */
    ProbedVideo probe(String url);
}
