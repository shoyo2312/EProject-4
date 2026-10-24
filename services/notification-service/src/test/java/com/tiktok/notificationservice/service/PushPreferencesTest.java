package com.tiktok.notificationservice.service;

import com.tiktok.notificationservice.entity.NotificationType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class PushPreferencesTest {

    /** UTC+7 all year. */
    private static final ZoneId HCM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Instant NOON = at("2026-10-24T12:00");

    private static Instant at(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(HCM).toInstant();
    }

    private static PushPreferences switches(boolean likes, boolean comments, boolean newFollowers) {
        return new PushPreferences(likes, comments, newFollowers, null, null, null);
    }

    private static PushPreferences asleep(String start, String end, ZoneId zone) {
        return new PushPreferences(true, true, true, LocalTime.parse(start), LocalTime.parse(end), zone);
    }

    @Test
    void likesSwitchGovernsLikeAndCommentLike() {
        PushPreferences noLikes = switches(false, true, true);

        assertThat(noLikes.allows(NotificationType.LIKE, NOON)).isFalse();
        assertThat(noLikes.allows(NotificationType.COMMENT_LIKE, NOON)).isFalse();
        assertThat(noLikes.allows(NotificationType.COMMENT, NOON)).isTrue();
        assertThat(noLikes.allows(NotificationType.NEW_FOLLOWER, NOON)).isTrue();
    }

    @Test
    void commentsSwitchGovernsComments() {
        PushPreferences noComments = switches(true, false, true);

        assertThat(noComments.allows(NotificationType.COMMENT, NOON)).isFalse();
        assertThat(noComments.allows(NotificationType.LIKE, NOON)).isTrue();
        assertThat(noComments.allows(NotificationType.COMMENT_LIKE, NOON)).isTrue();
    }

    @Test
    void newFollowersSwitchGovernsAllThreeFollowTypes() {
        PushPreferences noFollowers = switches(true, true, false);

        assertThat(noFollowers.allows(NotificationType.NEW_FOLLOWER, NOON)).isFalse();
        assertThat(noFollowers.allows(NotificationType.FOLLOW_REQUEST, NOON)).isFalse();
        assertThat(noFollowers.allows(NotificationType.FOLLOW_REQUEST_ACCEPTED, NOON)).isFalse();
        assertThat(noFollowers.allows(NotificationType.LIKE, NOON)).isTrue();
    }

    /** TikTok's list has no switch for these, so none of the three can turn them off. */
    @Test
    void shareStorySystemAlwaysPushWhenAwake() {
        PushPreferences allOff = switches(false, false, false);

        assertThat(allOff.allows(NotificationType.SHARE, NOON)).isTrue();
        assertThat(allOff.allows(NotificationType.STORY, NOON)).isTrue();
        assertThat(allOff.allows(NotificationType.SYSTEM, NOON)).isTrue();
    }

    @Test
    void sleepWindowAcrossMidnightMutes() {
        PushPreferences night = asleep("23:30", "07:30", HCM);

        assertThat(night.allows(NotificationType.SYSTEM, at("2026-10-24T02:00"))).isFalse();
        assertThat(night.allows(NotificationType.LIKE, at("2026-10-24T23:30"))).isFalse();
        assertThat(night.allows(NotificationType.LIKE, at("2026-10-24T08:00"))).isTrue();
        assertThat(night.allows(NotificationType.LIKE, at("2026-10-24T23:29"))).isTrue();
        assertThat(night.allows(NotificationType.LIKE, at("2026-10-24T07:30"))).isTrue();
    }

    @Test
    void sleepWindowWithinOneDayMutes() {
        PushPreferences early = asleep("01:00", "09:00", HCM);

        assertThat(early.allows(NotificationType.LIKE, at("2026-10-24T05:00"))).isFalse();
        assertThat(early.allows(NotificationType.LIKE, at("2026-10-24T00:59"))).isTrue();
        assertThat(early.allows(NotificationType.LIKE, at("2026-10-24T09:00"))).isTrue();
    }

    /** No heartbeat has said where the account is, so its night cannot be placed. */
    @Test
    void noZoneMeansNoMuting() {
        assertThat(asleep("23:30", "07:30", null).allows(NotificationType.LIKE, at("2026-10-24T02:00"))).isTrue();
    }
}
