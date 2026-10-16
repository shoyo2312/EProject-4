package com.tiktok.userservice.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.tiktok.moderation.text.MaxCodePoints;
import com.tiktok.moderation.text.TextSanitizer;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
 * <p>Clearing only applies to the three nullable fields. An explicit null on an audience, on
 * {@code weeklyScreenTimeDigest}, on {@code privateAccount} or on a comment filter is a
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

    @Min(value = 10, message = "must be at least 10 minutes")
    @Max(value = 1440, message = "must be at most 1440 minutes")
    private Integer screenTimeLimitMinutes;
    private boolean screenTimeLimitMinutesSet;

    @JsonFormat(pattern = TIME_FORMAT)
    private LocalTime sleepHoursStart;
    private boolean sleepHoursStartSet;

    @JsonFormat(pattern = TIME_FORMAT)
    private LocalTime sleepHoursEnd;
    private boolean sleepHoursEndSet;

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

    public void setSleepHoursStart(LocalTime sleepHoursStart) {
        this.sleepHoursStart = sleepHoursStart;
        this.sleepHoursStartSet = true;
    }

    public void setSleepHoursEnd(LocalTime sleepHoursEnd) {
        this.sleepHoursEnd = sleepHoursEnd;
        this.sleepHoursEndSet = true;
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
