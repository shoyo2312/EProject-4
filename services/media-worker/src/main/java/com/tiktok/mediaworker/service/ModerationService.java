package com.tiktok.mediaworker.service;

import com.tiktok.event.video.VideoModerationCompletedEvent;

/**
 * Scores a transcoded video's frames, or a story's media, and says what should happen to it.
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

    /**
     * The same promise for a story: always answers, and a check that cannot be completed is REVIEW,
     * which for a story means hidden until it expires.
     *
     * @param mediaKey the object under {@code stories/} as uploaded — stories are not transcoded
     * @param image    an image is the one frame; a video is probed and sampled across its length
     */
    StoryVerdict moderateStory(String storyId, String mediaKey, boolean image);

    /**
     * @param frames          addressed by the story's id in {@code videoId}; re-addressed by the caller
     * @param durationSeconds a video story's probed length, null for an image or when it could not be read
     */
    record StoryVerdict(VideoModerationCompletedEvent frames, Integer durationSeconds) {
    }
}
