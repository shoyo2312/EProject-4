package com.tiktok.notificationservice.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationTypeTest {

    /** Asking, withdrawing and asking again is one request to the owner, not three inbox entries. */
    @Test
    void followRequestsCollapseRepeatsLikeFollows() {
        assertThat(NotificationType.FOLLOW_REQUEST.collapsesRepeats()).isTrue();
        assertThat(NotificationType.FOLLOW_REQUEST_ACCEPTED.collapsesRepeats()).isTrue();
    }
}
