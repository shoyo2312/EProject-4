package com.tiktok.storyservice.entity;

import com.tiktok.event.video.ModerationVerdict;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StoryTest {

    @Test
    void pendingStoryExpiresAfter48Hours() {
        Instant now = Instant.parse("2026-10-20T10:00:00Z");

        Story story = Story.pending("s1", 7L, "stories/7/s1/media.jpg", "stories/7/1.jpg", StoryMediaType.IMAGE, "hi",
                StoryVisibility.FRIENDS, ModerationVerdict.REVIEW, now);

        assertThat(story.getStatus()).isEqualTo(StoryStatus.PENDING_MODERATION);
        assertThat(story.getExpiresAt()).isEqualTo(now.plus(Duration.ofHours(48)));
        assertThat(story.getMediaKey()).isEqualTo("stories/7/s1/media.jpg");
        assertThat(story.getUploadKey()).isEqualTo("stories/7/1.jpg");
        assertThat(story.getVisibility()).isEqualTo(StoryVisibility.FRIENDS);
        assertThat(story.getTextVerdict()).isEqualTo(ModerationVerdict.REVIEW);
        assertThat(story.getViewCount()).isZero();
    }
}
