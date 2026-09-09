package com.tiktok.adminservice.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommentTargetTest {

    @Test
    void roundTripsThroughTheStoredTargetId() {
        CommentTarget target = new CommentTarget(7_310_000_000_000_000_000L, 7_320_000_000_000_000_001L);

        assertThat(CommentTarget.parse(target.targetId())).isEqualTo(target);
    }

    /**
     * A bare comment id is the shape somebody would reach for first, and it is unusable: without
     * the video there is no partition to read. Refused loudly rather than parsed into a videoId of
     * whatever the string happened to be.
     */
    @Test
    void rejectsATargetIdWithoutBothIds() {
        assertThatThrownBy(() -> CommentTarget.parse("7320000000000000001"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CommentTarget.parse(":7320000000000000001"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CommentTarget.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
