package com.tiktok.adminservice.entity;

import com.tiktok.adminservice.exception.InvalidModerationTargetException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reports are matched to each other, to standing enforcement and to strikes by string equality,
 * so every spelling of one target has to come out as the same string.
 */
class ReportTargetTypeTest {

    @Test
    void user_everySpellingOfOneIdIsOneId() {
        assertThat(ReportTargetType.USER.canonicalTargetId("7")).isEqualTo("7");
        assertThat(ReportTargetType.USER.canonicalTargetId("007")).isEqualTo("7");
        assertThat(ReportTargetType.USER.canonicalTargetId("+7")).isEqualTo("7");
        assertThat(ReportTargetType.USER.canonicalTargetId(" 7 ")).isEqualTo("7");
    }

    @Test
    void user_refusesWhatIsNoUserId() {
        for (String id : new String[] {null, "", "abc", "0", "-7", "7a", "99999999999999999999"}) {
            assertThatThrownBy(() -> ReportTargetType.USER.canonicalTargetId(id))
                    .as(String.valueOf(id))
                    .isInstanceOf(InvalidModerationTargetException.class);
        }
    }

    /** video-service mints Snowflakes; a non-numeric id is still a string it may hold. */
    @Test
    void video_numericIdsAreSpelledOneWay_othersAreOnlyTrimmed() {
        assertThat(ReportTargetType.VIDEO.canonicalTargetId("7312458901234567")).isEqualTo("7312458901234567");
        assertThat(ReportTargetType.VIDEO.canonicalTargetId(" +0007312458901234567 ")).isEqualTo("7312458901234567");
        assertThat(ReportTargetType.VIDEO.canonicalTargetId(" v1 ")).isEqualTo("v1");
        assertThatThrownBy(() -> ReportTargetType.VIDEO.canonicalTargetId(" "))
                .isInstanceOf(InvalidModerationTargetException.class);
    }

    @Test
    void comment_bothHalvesAreSpelledOneWay() {
        assertThat(ReportTargetType.COMMENT.canonicalTargetId("007:+8")).isEqualTo("7:8");
        assertThat(ReportTargetType.COMMENT.canonicalTargetId(" 7:8 ")).isEqualTo("7:8");
        for (String id : new String[] {null, "7", "7:", ":8", "0:8", "7:-8", "a:8"}) {
            assertThatThrownBy(() -> ReportTargetType.COMMENT.canonicalTargetId(id))
                    .as(String.valueOf(id))
                    .isInstanceOf(InvalidModerationTargetException.class);
        }
    }
}
