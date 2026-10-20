package com.tiktok.event.story;

import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.event.video.VideoModerationCompletedEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StoryModerationCompletedEventTest {

    @Test
    void from_copiesVerdictScoresAndDuration() {
        VideoModerationCompletedEvent frames = VideoModerationCompletedEvent.of(
                "v", ModerationVerdict.REVIEW, "nsfw", 0.7, 2, 8, 120L, "m", "1", "r");

        StoryModerationCompletedEvent event = StoryModerationCompletedEvent.from("s1", frames, 42);

        assertThat(event.storyId()).isEqualTo("s1");
        assertThat(event.verdict()).isEqualTo(ModerationVerdict.REVIEW);
        assertThat(event.label()).isEqualTo("nsfw");
        assertThat(event.maxScore()).isEqualTo(0.7);
        assertThat(event.suspiciousFrames()).isEqualTo(2);
        assertThat(event.totalFrames()).isEqualTo(8);
        assertThat(event.processingTimeMs()).isEqualTo(120L);
        assertThat(event.model()).isEqualTo("m");
        assertThat(event.modelVersion()).isEqualTo("1");
        assertThat(event.reason()).isEqualTo("r");
        assertThat(event.durationSeconds()).isEqualTo(42);
        assertThat(event.eventId()).isNotNull().isNotEqualTo(frames.eventId());
    }
}
