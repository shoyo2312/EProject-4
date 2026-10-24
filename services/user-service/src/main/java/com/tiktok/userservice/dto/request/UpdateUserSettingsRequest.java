package com.tiktok.userservice.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.tiktok.moderation.text.MaxCodePoints;
import com.tiktok.moderation.text.TextSanitizer;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;

import java.time.LocalTime;
import java.util.List;

/**
 * PATCH body for {@code /users/me/settings}. Three-valued on purpose: a key can be <b>absent</b>
 * (leave the field alone), present and <b>null</b> (clear it, where the field is nullable), or
 * present with a value.
 *
 * <p>That is why this is a mutable class with hand-written setters rather than a record like every
 * other request DTO here. Jackson calls a setter only for keys the JSON actually carries, so the
 * {@code *Set} flags are the only reliable way to tell an absent key from an explicit null — a
 * record component is {@code null} in both cases, and {@code Optional} does not help either, since
 * Jackson fills a missing creator parameter with the deserializer's absent value, which for
 * {@code Optional} is the same {@code Optional.empty()} an explicit null produces.
 *
 * <p>Clearing only applies to the nullable fields. An explicit null on an audience, on
 * {@code weeklyScreenTimeDigest}, on {@code privateAccount}, on a notification switch or on a comment filter is a
 * {@code VALIDATION_ERROR} rather than a no-op: those columns are NOT NULL, and silently treating the
 * key as absent is how "I set it and it didn't save" happens. {@code commentKeywords} is cleared
 * with {@code []}.
 */
@Getter
public class UpdateUserSettingsRequest {

    /** The allowed values, as a regex so an unknown one names all three in the error message. */
    private static final String AUDIENCE = "EVERYONE|FRIENDS|NO_ONE";

    private static final String AUDIENCE_MESSAGE = "must be EVERYONE, FRIENDS or NO_ONE";

    /**
     * Wall-clock, minute precision, both ways — the same string the response carries back, so a
     * client can PATCH what it just read without reformatting it.
     */
    public static final String TIME_FORMAT = "HH:mm";

    @Pattern(regexp = AUDIENCE, message = AUDIENCE_MESSAGE)
    private String commentAudience;
    private boolean commentAudienceSet;

    @Pattern(regexp = AUDIENCE, message = AUDIENCE_MESSAGE)
    private String dmAudience;
    private boolean dmAudienceSet;

    /** The same daily limit every day. Null turns it off; at most one of this and the per-day list is set. */
    @Min(value = 10, message = "must be at least 10 minutes")
    @Max(value = 480, message = "must be at most 480 minutes")
    private Integer screenTimeLimitMinutes;
    private boolean screenTimeLimitMinutesSet;

    /** A daily limit per weekday, Monday first. */
    @Size(min = 7, max = 7, message = "must have one limit per weekday, Monday first")
    private List<@NotNull @Min(value = 10, message = "must be at least 10 minutes")
            @Max(value = 480, message = "must be at most 480 minutes") Integer> screenTimeLimitsByDay;
    private boolean screenTimeLimitsByDaySet;

    /**
     * Write-only: stored as a bcrypt hash, never returned. Needed to turn the daily limit on when
     * none is stored; sent while one is, it replaces it.
     */
    @Pattern(regexp = "\\d{4}", message = "must be 4 digits")
    private String screenTimePasscode;
    private boolean screenTimePasscodeSet;

    /**
     * The end is always this plus 8 hours, derived by the service. There is no {@code sleepHoursEnd}
     * key any more; one sent by an older client is an unknown property and ignored.
     */
    @JsonFormat(pattern = TIME_FORMAT)
    private LocalTime sleepHoursStart;
    private boolean sleepHoursStartSet;

    private Boolean pushLikes;
    private boolean pushLikesSet;

    private Boolean pushComments;
    private boolean pushCommentsSet;

    private Boolean pushNewFollowers;
    private boolean pushNewFollowersSet;

    private Boolean inAppLikes;
    private boolean inAppLikesSet;

    private Boolean inAppComments;
    private boolean inAppCommentsSet;

    private Boolean inAppNewFollowers;
    private boolean inAppNewFollowersSet;

    private Boolean inAppReposts;
    private boolean inAppRepostsSet;

    private Boolean weeklyScreenTimeDigest;
    private boolean weeklyScreenTimeDigestSet;

    /** Stored on the profile, not with the rest — see UserSettingsServiceImpl.changePrivacy. */
    private Boolean privateAccount;
    private boolean privateAccountSet;

    private Boolean filterSpamOffensive;
    private boolean filterSpamOffensiveSet;

    private Boolean filterAllComments;
    private boolean filterAllCommentsSet;

    /**
     * The whole list, replacing the stored one. Sanitized in the setter, before validation, so the
     * limit counts what is stored — the way a comment's own text is.
     */
    @Size(max = 50, message = "must have at most 50 keywords")
    private List<@NotBlank @MaxCodePoints(30) String> commentKeywords;
    private boolean commentKeywordsSet;

    public void setCommentAudience(String commentAudience) {
        this.commentAudience = commentAudience;
        this.commentAudienceSet = true;
    }

    public void setDmAudience(String dmAudience) {
        this.dmAudience = dmAudience;
        this.dmAudienceSet = true;
    }

    public void setScreenTimeLimitMinutes(Integer screenTimeLimitMinutes) {
        this.screenTimeLimitMinutes = screenTimeLimitMinutes;
        this.screenTimeLimitMinutesSet = true;
    }

    public void setScreenTimeLimitsByDay(List<Integer> screenTimeLimitsByDay) {
        this.screenTimeLimitsByDay = screenTimeLimitsByDay;
        this.screenTimeLimitsByDaySet = true;
    }

    public void setScreenTimePasscode(String screenTimePasscode) {
        this.screenTimePasscode = screenTimePasscode;
        this.screenTimePasscodeSet = true;
    }

    public void setSleepHoursStart(LocalTime sleepHoursStart) {
        this.sleepHoursStart = sleepHoursStart;
        this.sleepHoursStartSet = true;
    }

    public void setPushLikes(Boolean pushLikes) {
        this.pushLikes = pushLikes;
        this.pushLikesSet = true;
    }

    public void setPushComments(Boolean pushComments) {
        this.pushComments = pushComments;
        this.pushCommentsSet = true;
    }

    public void setPushNewFollowers(Boolean pushNewFollowers) {
        this.pushNewFollowers = pushNewFollowers;
        this.pushNewFollowersSet = true;
    }

    public void setInAppLikes(Boolean inAppLikes) {
        this.inAppLikes = inAppLikes;
        this.inAppLikesSet = true;
    }

    public void setInAppComments(Boolean inAppComments) {
        this.inAppComments = inAppComments;
        this.inAppCommentsSet = true;
    }

    public void setInAppNewFollowers(Boolean inAppNewFollowers) {
        this.inAppNewFollowers = inAppNewFollowers;
        this.inAppNewFollowersSet = true;
    }

    public void setInAppReposts(Boolean inAppReposts) {
        this.inAppReposts = inAppReposts;
        this.inAppRepostsSet = true;
    }

    public void setWeeklyScreenTimeDigest(Boolean weeklyScreenTimeDigest) {
        this.weeklyScreenTimeDigest = weeklyScreenTimeDigest;
        this.weeklyScreenTimeDigestSet = true;
    }

    public void setPrivateAccount(Boolean privateAccount) {
        this.privateAccount = privateAccount;
        this.privateAccountSet = true;
    }

    public void setFilterSpamOffensive(Boolean filterSpamOffensive) {
        this.filterSpamOffensive = filterSpamOffensive;
        this.filterSpamOffensiveSet = true;
    }

    public void setFilterAllComments(Boolean filterAllComments) {
        this.filterAllComments = filterAllComments;
        this.filterAllCommentsSet = true;
    }

    public void setCommentKeywords(List<String> commentKeywords) {
        this.commentKeywords = commentKeywords == null
                ? null
                : commentKeywords.stream().map(TextSanitizer::sanitizeLine).toList();
        this.commentKeywordsSet = true;
    }
}
