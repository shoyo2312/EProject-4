package com.tiktok.userservice.entity;

import com.tiktok.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalTime;
import java.util.List;

/**
 * One row per account that has ever changed a setting. Accounts that never have simply have no
 * row: {@link #defaultsFor} is what a read answers instead, and the first write inserts the patch
 * applied to it.
 *
 * <p>The defaults live here and nowhere else in Java — V10's {@code DEFAULT} clauses mirror them
 * for rows written outside this service, and the two have to be changed together.
 */
@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_settings")
public class UserSettings extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "comment_audience", nullable = false, length = 16)
    private SettingsAudience commentAudience;

    @Enumerated(EnumType.STRING)
    @Column(name = "dm_audience", nullable = false, length = 16)
    private SettingsAudience dmAudience;

    /** Null is off; see V10. */
    @Column(name = "screen_time_limit_minutes")
    private Integer screenTimeLimitMinutes;

    @Column(name = "sleep_hours_start")
    private LocalTime sleepHoursStart;

    @Column(name = "sleep_hours_end")
    private LocalTime sleepHoursEnd;

    @Column(name = "weekly_screen_time_digest", nullable = false)
    private boolean weeklyScreenTimeDigest;

    /** False: comments on this account's videos are not held for profanity, insults or spam. See V15. */
    @Column(name = "filter_spam_offensive", nullable = false)
    private boolean filterSpamOffensive;

    /** Every comment from someone else waits for this account's approval. */
    @Column(name = "filter_all_comments", nullable = false)
    private boolean filterAllComments;

    /** A comment containing one of these waits for approval. As typed, at most 50. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "comment_keywords", nullable = false, columnDefinition = "text[]")
    private List<String> commentKeywords;

    /**
     * What an account with no row has. Transient — a read maps this without persisting it, so
     * reading somebody's settings never writes.
     */
    public static UserSettings defaultsFor(Long userId) {
        return UserSettings.builder()
                .userId(userId)
                .commentAudience(SettingsAudience.EVERYONE)
                .dmAudience(SettingsAudience.EVERYONE)
                .screenTimeLimitMinutes(null)
                .sleepHoursStart(null)
                .sleepHoursEnd(null)
                .weeklyScreenTimeDigest(true)
                .filterSpamOffensive(true)
                .filterAllComments(false)
                .commentKeywords(List.of())
                .build();
    }

    /**
     * Total assignment, not a partial update: every argument is the final value of its field.
     * Unlike {@link UserProfile#updateProfile} there is no "null leaves it alone" rule here —
     * three of these fields are nullable and clearing one is a thing a client can ask for, so the
     * merge of patch over current state happens in the service, where it can be validated, and
     * this method only stores the result.
     */
    public void replaceWith(SettingsAudience commentAudience,
                            SettingsAudience dmAudience,
                            Integer screenTimeLimitMinutes,
                            LocalTime sleepHoursStart,
                            LocalTime sleepHoursEnd,
                            boolean weeklyScreenTimeDigest) {
        this.commentAudience = commentAudience;
        this.dmAudience = dmAudience;
        this.screenTimeLimitMinutes = screenTimeLimitMinutes;
        this.sleepHoursStart = sleepHoursStart;
        this.sleepHoursEnd = sleepHoursEnd;
        this.weeklyScreenTimeDigest = weeklyScreenTimeDigest;
    }

    /** The comment filters, assigned whole like {@link #replaceWith} — merged and validated by the service. */
    public void replaceCommentFilters(boolean filterSpamOffensive, boolean filterAllComments, List<String> commentKeywords) {
        this.filterSpamOffensive = filterSpamOffensive;
        this.filterAllComments = filterAllComments;
        this.commentKeywords = List.copyOf(commentKeywords);
    }
}
