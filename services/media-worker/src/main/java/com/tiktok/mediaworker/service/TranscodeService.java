package com.tiktok.mediaworker.service;

public interface TranscodeService {

    TranscodeResult transcode(String videoId, String rawFileUrl);

    /**
     * Whether this video's result has already been reported. VideoPublishedEvent is redelivered
     * by outbox resends and rebalances, and each copy would otherwise re-encode the whole video.
     */
    boolean alreadyTranscoded(String videoId);

    /** Called after the result is published, so a crash before it redoes the transcode. */
    void recordTranscoded(String videoId);
}
