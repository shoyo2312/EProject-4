package com.tiktok.event.story;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StoryActivatedEventTest {

    @Test
    void ofNamesOneIdPerStory() {
        assertThat(StoryActivatedEvent.of("s1", 7L).eventId())
                .isEqualTo(StoryActivatedEvent.of("s1", 7L).eventId());
        assertThat(StoryActivatedEvent.of("s1", 7L).eventId())
                .isNotEqualTo(StoryActivatedEvent.of("s2", 7L).eventId());
    }
}
