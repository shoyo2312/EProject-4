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

import java.time.Instant;
import java.time.LocalDate;
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

    /** The same limit every day. Null when off or per day; see V10 and V20. */
    @Column(name = "screen_time_limit_minutes")
    private Integer screenTimeLimitMinutes;

    /** One limit per weekday, Monday first. Null when off or the same every day; see V20. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "screen_time_limits_by_day", columnDefinition = "integer[]")
    private List<Integer> screenTimeLimitsByDay;

    /** bcrypt of the 4-digit passcode that lets the owner past the limit. Never leaves the service. */
    @Column(name = "screen_time_passcode_hash", length = 60)
    private String screenTimePasscodeHash;

    /** Wrong passcodes since the last right one or the last lock. */
    @Column(name = "screen_time_passcode_failures", nullable = false)
    private int screenTimePasscodeFailures;

    @Column(name = "screen_time_passcode_locked_until")
    private Instant screenTimePasscodeLockedUntil;

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

    /** Push (phone and desktop) for likes on a video or a comment. See V18. */
    @Column(name = "push_likes", nullable = false)
    private boolean pushLikes;

    @Column(name = "push_comments", nullable = false)
    private boolean pushComments;

    /** New followers, follow requests and accepted requests. */
    @Column(name = "push_new_followers", nullable = false)
    private boolean pushNewFollowers;

    /** In-app banner switches, same grouping as the push ones plus reposts. */
    @Column(name = "in_app_likes", nullable = false)
    private boolean inAppLikes;

    @Column(name = "in_app_comments", nullable = false)
    private boolean inAppComments;

    @Column(name = "in_app_new_followers", nullable = false)
    private boolean inAppNewFollowers;

    @Column(name = "in_app_reposts", nullable = false)
    private boolean inAppReposts;

    /**
     * The browser's IANA zone. Read-only here: only the heartbeat's native upsert writes it, so an
     * entity read before that upsert can never save the old value back over it.
     */
    @Column(name = "time_zone", insertable = false, updatable = false)
    private String timeZone;

    /**
     * The Monday of the last week the weekly update was sent (or skipped) for. Read-only here like
     * {@link #timeZone}: only the digest job's claim writes it. See V22.
     */
    @Column(name = "last_digest_week", insertable = false, updatable = false)
    private LocalDate lastDigestWeek;

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
                .pushLikes(true)
                .pushComments(true)
                .pushNewFollowers(true)
                .inAppLikes(true)
                .inAppComments(true)
                .inAppNewFollowers(true)
                .inAppReposts(true)
                .build();
    }

    /**
     * Total assignment, not a partial update: every argument is the final value of its field.
     * Unlike {@link UserProfile#updateProfile} there is no "null leaves it alone" rule here —
     * the sleep hours are nullable and clearing them is a thing a client can ask for, so the
     * merge of patch over current state happens in the service, where it can be validated, and
     * this method only stores the result.
     */
    public void replaceWith(SettingsAudience commentAudience,
                            SettingsAudience dmAudience,
                            LocalTime sleepHoursStart,
                            LocalTime sleepHoursEnd,
                            boolean weeklyScreenTimeDigest) {
        this.commentAudience = commentAudience;
        this.dmAudience = dmAudience;
        this.sleepHoursStart = sleepHoursStart;
        this.sleepHoursEnd = sleepHoursEnd;
        this.weeklyScreenTimeDigest = weeklyScreenTimeDigest;
    }

    /** The daily limit and its passcode, assigned whole like {@link #replaceWith} — merged and validated by the service. */
    public void replaceScreenTimeLimit(Integer minutes, List<Integer> byDay, String passcodeHash) {
        this.screenTimeLimitMinutes = minutes;
        this.screenTimeLimitsByDay = byDay == null ? null : List.copyOf(byDay);
        this.screenTimePasscodeHash = passcodeHash;
    }

    /** The wrong-passcode count and lock, as the service decided them. */
    public void replacePasscodeAttempts(int failures, Instant lockedUntil) {
        this.screenTimePasscodeFailures = failures;
        this.screenTimePasscodeLockedUntil = lockedUntil;
    }

    /** The notification switches, assigned whole like {@link #replaceWith} — merged by the service. */
    public void replaceNotificationSwitches(boolean pushLikes, boolean pushComments, boolean pushNewFollowers,
                                            boolean inAppLikes, boolean inAppComments,
                                            boolean inAppNewFollowers, boolean inAppReposts) {
        this.pushLikes = pushLikes;
        this.pushComments = pushComments;
        this.pushNewFollowers = pushNewFollowers;
        this.inAppLikes = inAppLikes;
        this.inAppComments = inAppComments;
        this.inAppNewFollowers = inAppNewFollowers;
        this.inAppReposts = inAppReposts;
    }

    /** The comment filters, assigned whole like {@link #replaceWith} — merged and validated by the service. */
    public void replaceCommentFilters(boolean filterSpamOffensive, boolean filterAllComments, List<String> commentKeywords) {
        this.filterSpamOffensive = filterSpamOffensive;
        this.filterAllComments = filterAllComments;
        this.commentKeywords = List.copyOf(commentKeywords);
    }
}
