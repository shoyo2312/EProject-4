package com.tiktok.mediaworker.service;

import com.tiktok.event.video.VideoPublishedEvent;

public interface TranscodeService {

    /**
     * @param edit trim and speed from the app's editor, applied before anything else; null plays
     *             the upload as recorded
     */
    TranscodeResult transcode(String videoId, String rawFileUrl, VideoPublishedEvent.Edit edit);

    /**
     * Whether this video's result has already been reported. VideoPublishedEvent is redelivered
     * by outbox resends and rebalances, and each copy would otherwise re-encode the whole video.
     */
    boolean alreadyTranscoded(String videoId);

    /** Called after the result is published, so a crash before it redoes the transcode. */
    void recordTranscoded(String videoId);
}
