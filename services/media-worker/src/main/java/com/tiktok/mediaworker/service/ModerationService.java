package com.tiktok.mediaworker.service;

import com.tiktok.event.video.VideoModerationCompletedEvent;

/**
 * Scores a transcoded video's frames and says what should happen to it.
 */
public interface ModerationService {

    /**
     * Always answers. There is no failure mode that leaves a video without a verdict, because a
     * video without a verdict is a video stuck at PENDING_MODERATION with nothing that would ever
     * move it: when the check cannot be completed the answer is REVIEW with the reason attached,
     * which puts it in front of a human instead.
     *
     * @param durationSeconds the video's length, so the frame samples span all of it
     */
    VideoModerationCompletedEvent moderate(String videoId, Integer durationSeconds);
}
